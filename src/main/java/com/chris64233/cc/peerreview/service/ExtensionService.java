package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import com.chris64233.cc.peerreview.domain.ExtensionEvent;
import com.chris64233.cc.peerreview.domain.ExtensionEventType;
import com.chris64233.cc.peerreview.exception.BusinessRuleException;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ExtensionEventRepository;
import com.chris64233.cc.peerreview.web.dto.ExtensionEventDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 延期管理。申请、批准、拒绝全部落为不可变事件（只追加、不修改）；
 * 每个任务最多批准一次延期，批准天数不超过配置上限。
 *
 * 幂等约定（面向重试与外部事件重复投递）：
 * - 已有待审批申请时重复申请：直接返回当前事件时间线，不产生新事件；
 * - 已批准后重复批准、已作出审批决定后重复审批：返回当前事件时间线，不产生新事件。
 */
@Service
public class ExtensionService {

    private final AssignmentRepository assignmentRepository;
    private final ExtensionEventRepository extensionEventRepository;
    private final DeadlinePolicy deadlinePolicy;
    private final Clock clock;

    public ExtensionService(AssignmentRepository assignmentRepository,
                            ExtensionEventRepository extensionEventRepository,
                            DeadlinePolicy deadlinePolicy,
                            Clock clock) {
        this.assignmentRepository = assignmentRepository;
        this.extensionEventRepository = extensionEventRepository;
        this.deadlinePolicy = deadlinePolicy;
        this.clock = clock;
    }

    @Transactional
    public List<ExtensionEventDto> request(String manuscriptNo, String reviewerCode,
                                           int days, String reason) {
        Assignment assignment = lockAssignment(manuscriptNo, reviewerCode);
        if (assignment.getStatus() != AssignmentStatus.ACTIVE) {
            throw new BusinessRuleException("仅进行中的任务可以申请延期，当前状态: " + assignment.getStatus());
        }
        if (days > deadlinePolicy.maxExtensionDays()) {
            throw new BusinessRuleException(String.format(
                    "延期天数超过上限：申请 %d 天，上限 %d 天", days, deadlinePolicy.maxExtensionDays()));
        }
        List<ExtensionEvent> events = eventsOf(assignment);
        if (hasApproved(events)) {
            throw new BusinessRuleException("该任务已批准过一次延期，不能再次申请");
        }
        if (pendingRequest(events) != null) {
            // 幂等：已有待审批申请，重复投递不产生新事件。
            return toDtos(events);
        }
        extensionEventRepository.save(new ExtensionEvent(
                assignment, ExtensionEventType.REQUESTED, days, reason, reviewerCode,
                null, null, clock.instant()));
        return toDtos(eventsOf(assignment));
    }

    @Transactional
    public List<ExtensionEventDto> approve(String manuscriptNo, String reviewerCode,
                                           String editor, Integer days, String reason) {
        Assignment assignment = lockAssignment(manuscriptNo, reviewerCode);
        List<ExtensionEvent> events = eventsOf(assignment);
        if (hasApproved(events)) {
            // 幂等：延期已批准，重复批准不改变截止期、不追加事件。
            return toDtos(events);
        }
        ExtensionEvent pending = pendingRequest(events);
        if (pending == null) {
            throw new BusinessRuleException("没有待审批的延期申请");
        }
        if (assignment.getStatus() != AssignmentStatus.ACTIVE) {
            throw new BusinessRuleException("任务已结束（" + assignment.getStatus() + "），不能批准延期");
        }
        int approvedDays = days != null ? days : pending.getDays();
        if (approvedDays < 1 || approvedDays > deadlinePolicy.maxExtensionDays()) {
            throw new BusinessRuleException(String.format(
                    "批准天数必须在 1 到 %d 天之间: %d", deadlinePolicy.maxExtensionDays(), approvedDays));
        }

        Instant now = clock.instant();
        Instant previousDue = assignment.getDueAt();
        Instant newDue = previousDue.plus(approvedDays, ChronoUnit.DAYS);
        int extensionDays = assignment.getExtensionDays() + approvedDays;
        assignment.setExtensionDays(extensionDays);
        assignment.setDueAt(newDue);
        assignment.setDeadlineBasis(deadlinePolicy.basis(
                assignment.getReviewRound(), assignment.getRoundDays(),
                assignment.getReviewerDays(), extensionDays));
        assignment.setUpdatedAt(now);
        assignmentRepository.save(assignment);

        extensionEventRepository.save(new ExtensionEvent(
                assignment, ExtensionEventType.APPROVED, approvedDays, reason, editor,
                previousDue, newDue, now));
        return toDtos(eventsOf(assignment));
    }

    @Transactional
    public List<ExtensionEventDto> reject(String manuscriptNo, String reviewerCode,
                                          String editor, String reason) {
        Assignment assignment = lockAssignment(manuscriptNo, reviewerCode);
        List<ExtensionEvent> events = eventsOf(assignment);
        if (pendingRequest(events) == null) {
            // 幂等：已有审批决定（批准或拒绝）时重复拒绝不追加事件。
            if (hasApproved(events)
                    || events.stream().anyMatch(e -> e.getType() == ExtensionEventType.REJECTED)) {
                return toDtos(events);
            }
            throw new BusinessRuleException("没有待审批的延期申请");
        }
        extensionEventRepository.save(new ExtensionEvent(
                assignment, ExtensionEventType.REJECTED, null, reason, editor,
                null, null, clock.instant()));
        return toDtos(eventsOf(assignment));
    }

    @Transactional(readOnly = true)
    public List<ExtensionEventDto> listEvents(String manuscriptNo, String reviewerCode) {
        List<Assignment> found = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode(manuscriptNo, reviewerCode);
        if (found.isEmpty()) {
            throw new BusinessRuleException("该评审人与此稿件之间不存在分配记录: " + reviewerCode);
        }
        return toDtos(eventsOf(found.get(0)));
    }

    private Assignment lockAssignment(String manuscriptNo, String reviewerCode) {
        List<Assignment> found = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode(manuscriptNo, reviewerCode);
        if (found.isEmpty()) {
            throw new BusinessRuleException("该评审人与此稿件之间不存在分配记录: " + reviewerCode);
        }
        // 行级写锁：与逾期扫描的 CAS 互斥，审批期间任务状态不会被并发改变。
        return assignmentRepository.findByIdForUpdate(found.get(0).getId()).orElseThrow();
    }

    private List<ExtensionEvent> eventsOf(Assignment assignment) {
        return extensionEventRepository.findByAssignmentIdOrderByIdAsc(assignment.getId());
    }

    private static boolean hasApproved(List<ExtensionEvent> events) {
        return events.stream().anyMatch(e -> e.getType() == ExtensionEventType.APPROVED);
    }

    /** 最近一次申请若尚无审批决定，则为待审批申请。 */
    private static ExtensionEvent pendingRequest(List<ExtensionEvent> events) {
        ExtensionEvent pending = null;
        for (ExtensionEvent event : events) {
            if (event.getType() == ExtensionEventType.REQUESTED) {
                pending = event;
            } else {
                pending = null;
            }
        }
        return pending;
    }

    private static List<ExtensionEventDto> toDtos(List<ExtensionEvent> events) {
        return events.stream().map(AssignmentService::toEventDto).toList();
    }
}
