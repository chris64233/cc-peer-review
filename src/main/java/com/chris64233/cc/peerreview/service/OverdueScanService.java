package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.config.ReviewDeadlineProperties;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.web.dto.OverdueScanResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * 逾期扫描器：分批找出已到截止期仍未提交的有效任务，逐条在独立事务中处理。
 * 幂等与重启恢复：扫描查询只读当前状态，每条任务处理前在锁内复检，
 * 已处理过的任务自然被跳过；服务重启后再次扫描即可从断点继续。
 * 单条失败不影响同批其他任务，下一批会重试。
 */
@Service
public class OverdueScanService {

    private static final Logger log = LoggerFactory.getLogger(OverdueScanService.class);

    private final AssignmentRepository assignmentRepository;
    private final AssignmentService assignmentService;
    private final ReviewDeadlineProperties properties;
    private final Clock clock;

    public OverdueScanService(AssignmentRepository assignmentRepository,
                              AssignmentService assignmentService,
                              ReviewDeadlineProperties properties,
                              Clock clock) {
        this.assignmentRepository = assignmentRepository;
        this.assignmentService = assignmentService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 执行一次扫描（本方法不开事务：查询由仓库层自管事务，
     * 每条任务的处理各自独立提交）。
     */
    public OverdueScanResultDto scanOnce() {
        List<Long> ids = assignmentRepository.findOverdueIds(
                clock.instant(), PageRequest.of(0, properties.getScanBatchSize()));
        int processed = 0;
        for (Long id : ids) {
            try {
                if (assignmentService.processOverdue(id)) {
                    processed++;
                }
            } catch (Exception e) {
                // 单条失败（如锁等待超时）不中断批次，下一轮扫描会重试。
                log.warn("逾期处理失败，任务#{}，留待下一轮: {}", id, e.getMessage());
            }
        }
        return new OverdueScanResultDto(ids.size(), processed);
    }
}
