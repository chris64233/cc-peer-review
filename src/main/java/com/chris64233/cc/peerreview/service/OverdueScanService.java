package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.web.dto.ScanResultDto;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 逾期扫描。本身不开事务：每条任务由 AssignmentService.processOverdue 在独立事务中处理，
 * 单条失败或实例宕机只影响当前任务，重启后再次扫描即可从未完成任务继续。
 * 扫描整体幂等：已转终态的任务不会被重复处理，可安全地由外部调度器高频触发。
 */
@Service
public class OverdueScanService {

    private final AssignmentRepository assignmentRepository;
    private final AssignmentService assignmentService;
    private final Clock clock;

    public OverdueScanService(AssignmentRepository assignmentRepository,
                              AssignmentService assignmentService,
                              Clock clock) {
        this.assignmentRepository = assignmentRepository;
        this.assignmentService = assignmentService;
        this.clock = clock;
    }

    public ScanResultDto scanOverdue() {
        Instant scannedAt = clock.instant();
        List<Long> dueIds = assignmentRepository.findDueActiveIds(scannedAt);

        int overdueMarked = 0;
        int reassigned = 0;
        int skipped = 0;
        List<Long> overdueAssignmentIds = new ArrayList<>();
        for (Long assignmentId : dueIds) {
            OverdueOutcome outcome;
            try {
                outcome = assignmentService.processOverdue(assignmentId);
            } catch (RuntimeException ex) {
                // 单条失败（如锁等待超时）不阻塞批次，留待下一扫描周期重试。
                skipped++;
                continue;
            }
            switch (outcome) {
                case SKIPPED -> skipped++;
                case OVERDUE_REASSIGNED -> {
                    overdueMarked++;
                    reassigned++;
                    overdueAssignmentIds.add(assignmentId);
                }
                case OVERDUE_NO_REPLACEMENT -> {
                    overdueMarked++;
                    overdueAssignmentIds.add(assignmentId);
                }
            }
        }
        return new ScanResultDto(scannedAt, dueIds.size(), overdueMarked, reassigned, skipped,
                overdueAssignmentIds);
    }
}
