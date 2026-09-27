package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.config.ReviewDeadlineProperties;
import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentEvent;
import com.chris64233.cc.peerreview.domain.AssignmentEventType;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.domain.Submission;
import com.chris64233.cc.peerreview.domain.SubmissionStatus;
import com.chris64233.cc.peerreview.exception.BusinessRuleException;
import com.chris64233.cc.peerreview.exception.ConflictException;
import com.chris64233.cc.peerreview.repository.AssignmentEventRepository;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import com.chris64233.cc.peerreview.web.dto.AssignmentDto;
import com.chris64233.cc.peerreview.web.dto.AssignmentEventDto;
import com.chris64233.cc.peerreview.web.dto.ExtensionApprovalRequest;
import com.chris64233.cc.peerreview.web.dto.ExtensionRejectionRequest;
import com.chris64233.cc.peerreview.web.dto.ExtensionRequestBody;
import com.chris64233.cc.peerreview.web.dto.SubmissionAssignmentDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AssignmentService {

    private static final String ACTOR_SYSTEM = "system";

    private final SubmissionRepository submissionRepository;
    private final ReviewerRepository reviewerRepository;
    private final AssignmentRepository assignmentRepository;
    private final AssignmentEventRepository eventRepository;
    private final AssignmentEventService eventService;
    private final DeadlinePolicy deadlinePolicy;
    private final ReviewDeadlineProperties deadlineProperties;
    private final Clock clock;

    public AssignmentService(SubmissionRepository submissionRepository,
                             ReviewerRepository reviewerRepository,
                             AssignmentRepository assignmentRepository,
                             AssignmentEventRepository eventRepository,
                             AssignmentEventService eventService,
                             DeadlinePolicy deadlinePolicy,
                             ReviewDeadlineProperties deadlineProperties,
                             Clock clock) {
        this.submissionRepository = submissionRepository;
        this.reviewerRepository = reviewerRepository;
        this.assignmentRepository = assignmentRepository;
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.deadlinePolicy = deadlinePolicy;
        this.deadlineProperties = deadlineProperties;
        this.clock = clock;
    }

    /**
     * 自动为稿件分配评审人。仅允许对尚无任何分配记录的稿件执行；
     * 候选不足时抛出 BusinessRuleException，事务回滚，任何人的工作量都不增加。
     * 每个任务创建时按稿件轮次与评审人规则计算截止时间，并保存计算依据。
     */
    @Transactional
    public SubmissionAssignmentDto autoAssign(String manuscriptNo) {
        Submission submission = lockSubmission(manuscriptNo);

        if (!assignmentRepository.findBySubmissionManuscriptNo(manuscriptNo).isEmpty()) {
            throw new ConflictException("稿件已存在分配记录，不能重复自动分配: " + manuscriptNo);
        }

        List<Reviewer> chosen = chooseReviewers(submission, submission.getRequiredReviewers(), true);
        for (Reviewer reviewer : chosen) {
            Assignment assignment = createActiveAssignment(submission, reviewer);
            eventService.record(assignment, AssignmentEventType.ASSIGNED,
                    ACTOR_SYSTEM, "自动分配", clock.instant());
        }
        refreshStatus(submission);
        return getDetail(manuscriptNo);
    }

    /**
     * 评审人拒绝任务：将任务标记为 DECLINED，并按相同规则补选一个从未分配过的评审人。
     * 整个过程在同一事务内原子完成；对非 ACTIVE 任务重复拒绝幂等，无任何副作用。
     */
    @Transactional
    public SubmissionAssignmentDto decline(String manuscriptNo, String reviewerCode) {
        Submission submission = lockSubmission(manuscriptNo);
        Assignment assignment = lockAssignment(manuscriptNo, reviewerCode);

        if (assignment.getStatus() == AssignmentStatus.ACTIVE) {
            assignment.setStatus(AssignmentStatus.DECLINED);
            assignment.setUpdatedAt(clock.instant());
            assignmentRepository.save(assignment);
            eventService.record(assignment, AssignmentEventType.DECLINED,
                    reviewerCode, "评审人拒绝任务", clock.instant());

            // 拒绝已落库后再挑选替补，ACTIVE 计数立即释放；无替补时不抛异常，保留已有有效分配。
            List<Reviewer> replacements = chooseReviewers(submission, 1, false);
            if (!replacements.isEmpty()) {
                Assignment replacement = createActiveAssignment(submission, replacements.get(0));
                eventService.record(replacement, AssignmentEventType.ASSIGNED,
                        ACTOR_SYSTEM, "替补拒绝任务#" + assignment.getId(), clock.instant());
            }
        }
        refreshStatus(submission);
        return getDetail(manuscriptNo);
    }

    /**
     * 提交评审意见。仅 ACTIVE 任务可提交；重复提交幂等；
     * 已逾期/已拒绝任务上的迟到意见只留痕（独立事务），不得恢复任务。
     */
    @Transactional
    public SubmissionAssignmentDto submitReview(String manuscriptNo, String reviewerCode, String comment) {
        Submission submission = lockSubmission(manuscriptNo);
        Assignment assignment = lockAssignment(manuscriptNo, reviewerCode);
        Instant now = clock.instant();

        if (assignment.getStatus() == AssignmentStatus.ACTIVE) {
            assignment.setStatus(AssignmentStatus.SUBMITTED);
            assignment.setSubmittedAt(now);
            assignment.setReviewComment(comment);
            assignment.setUpdatedAt(now);
            assignmentRepository.save(assignment);
            eventService.record(assignment, AssignmentEventType.SUBMITTED,
                    reviewerCode, "提交评审意见", now);
            refreshStatus(submission);
        } else if (assignment.getStatus() == AssignmentStatus.SUBMITTED) {
            // 重复提交幂等：不改变任何状态。
        } else {
            // 任务已进入终态（逾期/拒绝）：迟到意见不得恢复任务，事件独立提交留痕。
            eventService.recordIndependent(assignment, AssignmentEventType.LATE_SUBMITTED,
                    reviewerCode, "任务已处于终态 " + assignment.getStatus() + "，迟到意见不被接受", now);
            throw new ConflictException(
                    "任务已处于终态 " + assignment.getStatus() + "，迟到意见不能恢复任务: " + reviewerCode);
        }
        return getDetail(manuscriptNo);
    }

    /**
     * 评审人申请延期。仅 ACTIVE 任务可申请；已有待审批申请或已批准过延期的任务不可重复申请；
     * 申请天数不得超过配置上限。申请本身即形成不可变事件。
     */
    @Transactional
    public SubmissionAssignmentDto requestExtension(String manuscriptNo, String reviewerCode,
                                                    ExtensionRequestBody request) {
        lockSubmission(manuscriptNo);
        Assignment assignment = lockAssignment(manuscriptNo, reviewerCode);

        if (assignment.getStatus() != AssignmentStatus.ACTIVE) {
            throw new ConflictException("仅有效任务可以申请延期，当前状态: " + assignment.getStatus());
        }
        if (request.requestedDays() > deadlineProperties.getExtensionMaxDays()) {
            throw new BusinessRuleException(String.format(
                    "延期天数超过上限：申请 %d 天，上限 %d 天",
                    request.requestedDays(), deadlineProperties.getExtensionMaxDays()));
        }
        ExtensionState state = extensionState(assignment.getId());
        if (state.approved()) {
            throw new ConflictException("该任务已批准过延期，每个任务至多批准一次");
        }
        if (state.pending()) {
            throw new ConflictException("该任务已有待审批的延期申请");
        }

        eventService.record(assignment, AssignmentEventType.EXTENSION_REQUESTED,
                request.requestedBy(),
                "申请延期 " + request.requestedDays() + " 天"
                        + (request.reason() == null ? "" : "，理由: " + request.reason()),
                clock.instant());
        return getDetail(manuscriptNo);
    }

    /**
     * 编辑批准延期：须存在待审批申请、此前未批准过、批准天数在上限内。
     * 批准后截止时间顺延并在计算依据上追加记录；与逾期扫描互斥——任务已逾期则批准失败。
     */
    @Transactional
    public SubmissionAssignmentDto approveExtension(String manuscriptNo, String reviewerCode,
                                                    ExtensionApprovalRequest request) {
        lockSubmission(manuscriptNo);
        Assignment assignment = lockAssignment(manuscriptNo, reviewerCode);

        if (assignment.getStatus() != AssignmentStatus.ACTIVE) {
            throw new ConflictException("任务已处于终态 " + assignment.getStatus() + "，不能批准延期");
        }
        if (request.approvedDays() > deadlineProperties.getExtensionMaxDays()) {
            throw new BusinessRuleException(String.format(
                    "批准天数超过上限：批准 %d 天，上限 %d 天",
                    request.approvedDays(), deadlineProperties.getExtensionMaxDays()));
        }
        ExtensionState state = extensionState(assignment.getId());
        if (state.approved()) {
            throw new ConflictException("该任务已批准过延期，每个任务至多批准一次");
        }
        if (!state.pending()) {
            throw new BusinessRuleException("该任务没有待审批的延期申请");
        }

        Instant now = clock.instant();
        Instant newDueAt = assignment.getDueAt().plus(Duration.ofDays(request.approvedDays()));
        assignment.setDueAt(newDueAt);
        assignment.setDeadlineBasis(assignment.getDeadlineBasis()
                + " | extension=+" + request.approvedDays() + "d by " + request.decidedBy()
                + ", newDueAt=" + newDueAt);
        assignment.setUpdatedAt(now);
        assignmentRepository.save(assignment);
        eventService.record(assignment, AssignmentEventType.EXTENSION_APPROVED,
                request.decidedBy(),
                "批准延期 " + request.approvedDays() + " 天，新截止时间 " + newDueAt, now);
        return getDetail(manuscriptNo);
    }

    /**
     * 编辑拒绝延期申请：须存在待审批申请。拒绝后评审人可以重新申请。
     */
    @Transactional
    public SubmissionAssignmentDto rejectExtension(String manuscriptNo, String reviewerCode,
                                                   ExtensionRejectionRequest request) {
        lockSubmission(manuscriptNo);
        Assignment assignment = lockAssignment(manuscriptNo, reviewerCode);

        if (assignment.getStatus() != AssignmentStatus.ACTIVE) {
            throw new ConflictException("任务已处于终态 " + assignment.getStatus() + "，不能审批延期");
        }
        ExtensionState state = extensionState(assignment.getId());
        if (!state.pending()) {
            throw new BusinessRuleException("该任务没有待审批的延期申请");
        }

        eventService.record(assignment, AssignmentEventType.EXTENSION_REJECTED,
                request.decidedBy(),
                "拒绝延期申请" + (request.reason() == null ? "" : "，理由: " + request.reason()),
                clock.instant());
        return getDetail(manuscriptNo);
    }

    /**
     * 逾期处理（由扫描器逐条调用，每条独立事务，失败不影响同批其他任务）：
     * 将 ACTIVE 且已过截止时间的任务转为 OVERDUE，释放评审人负载，
     * 并按既有冲突/主题/工作量规则原子补选替补；无替补则稿件进入缺员。
     * 重复处理幂等：非 ACTIVE 或未到期的任务直接跳过。
     *
     * @return true 表示本次确实执行了逾期转换
     */
    @Transactional
    public boolean processOverdue(Long assignmentId) {
        Assignment probe = assignmentRepository.findById(assignmentId).orElse(null);
        if (probe == null) {
            return false;
        }
        Submission submission = lockSubmission(probe.getSubmission().getManuscriptNo());
        Assignment assignment = assignmentRepository.findByIdForUpdate(assignmentId).orElse(null);
        if (assignment == null) {
            return false;
        }

        Instant now = clock.instant();
        if (assignment.getStatus() != AssignmentStatus.ACTIVE
                || assignment.getDueAt() == null
                || assignment.getDueAt().isAfter(now)) {
            // 幂等护栏：已提交/已逾期/已拒绝，或批准延期后截止时间推后到未来。
            return false;
        }

        String reason = "截至 " + assignment.getDueAt() + " 未提交评审意见，任务逾期";
        assignment.setStatus(AssignmentStatus.OVERDUE);
        assignment.setOverdueReason(reason);
        assignment.setUpdatedAt(now);
        assignmentRepository.save(assignment);
        eventService.record(assignment, AssignmentEventType.OVERDUE, ACTOR_SYSTEM, reason, now);

        // 逾期已落库，ACTIVE 计数立即释放；按相同规则补选从未分配过该稿件的评审人。
        List<Reviewer> replacements = chooseReviewers(submission, 1, false);
        if (!replacements.isEmpty()) {
            Reviewer replacement = replacements.get(0);
            Assignment newAssignment = createActiveAssignment(submission, replacement);
            eventService.record(newAssignment, AssignmentEventType.ASSIGNED,
                    ACTOR_SYSTEM, "替补逾期任务#" + assignment.getId(), now);
            eventService.record(assignment, AssignmentEventType.REASSIGNED,
                    ACTOR_SYSTEM,
                    "改派给 " + replacement.getCode() + "（新任务#" + newAssignment.getId() + "）", now);
        }
        refreshStatus(submission);
        return true;
    }

    @Transactional(readOnly = true)
    public SubmissionAssignmentDto getDetail(String manuscriptNo) {
        Submission submission = submissionRepository.findByManuscriptNo(manuscriptNo)
                .orElseThrow(() -> new com.chris64233.cc.peerreview.exception.ResourceNotFoundException(
                        "投稿不存在: " + manuscriptNo));
        List<Assignment> assignments = assignmentRepository.findBySubmissionManuscriptNo(manuscriptNo);
        long active = assignments.stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE
                        || a.getStatus() == AssignmentStatus.SUBMITTED)
                .count();
        List<AssignmentDto> dtos = assignments.stream().map(this::toDto).toList();
        List<AssignmentEventDto> events = eventRepository.findByManuscriptNoOrderByIdAsc(manuscriptNo)
                .stream().map(this::toEventDto).toList();
        return new SubmissionAssignmentDto(
                manuscriptNo,
                submission.getStatus().name(),
                submission.getRequiredReviewers(),
                active,
                dtos,
                events);
    }

    /** 创建带截止期的有效任务：按稿件轮次与评审人规则计算，并保存计算依据。 */
    private Assignment createActiveAssignment(Submission submission, Reviewer reviewer) {
        Instant now = clock.instant();
        DeadlinePolicy.Computation computation = deadlinePolicy.compute(
                submission.getRound(), reviewer.getReviewDays(), now);
        return assignmentRepository.save(new Assignment(
                submission, reviewer, AssignmentStatus.ACTIVE, now,
                computation.dueAt(), computation.basis()));
    }

    private Submission lockSubmission(String manuscriptNo) {
        return submissionRepository.findByManuscriptNoForUpdate(manuscriptNo)
                .orElseThrow(() -> new com.chris64233.cc.peerreview.exception.ResourceNotFoundException(
                        "投稿不存在: " + manuscriptNo));
    }

    /**
     * 先按稿件号+评审人编号定位任务，再对任务行加悲观写锁。
     * 全系统统一 稿件 → 任务 → 评审人 的加锁顺序，提交/延期审批/逾期扫描互不死锁。
     */
    private Assignment lockAssignment(String manuscriptNo, String reviewerCode) {
        List<Assignment> targets = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode(manuscriptNo, reviewerCode);
        if (targets.isEmpty()) {
            throw new BusinessRuleException(
                    "该评审人与此稿件之间不存在分配记录: " + reviewerCode);
        }
        return assignmentRepository.findByIdForUpdate(targets.get(0).getId())
                .orElseThrow(() -> new BusinessRuleException(
                        "该评审人与此稿件之间不存在分配记录: " + reviewerCode));
    }

    /** 延期状态：是否存在待审批申请、是否已批准过（每个任务至多批准一次）。 */
    private ExtensionState extensionState(Long assignmentId) {
        List<AssignmentEvent> events = eventRepository.findByAssignmentIdAndTypeInOrderByIdAsc(
                assignmentId, List.of(
                        AssignmentEventType.EXTENSION_REQUESTED,
                        AssignmentEventType.EXTENSION_APPROVED,
                        AssignmentEventType.EXTENSION_REJECTED));
        long requested = events.stream()
                .filter(e -> e.getType() == AssignmentEventType.EXTENSION_REQUESTED).count();
        long approved = events.stream()
                .filter(e -> e.getType() == AssignmentEventType.EXTENSION_APPROVED).count();
        long rejected = events.stream()
                .filter(e -> e.getType() == AssignmentEventType.EXTENSION_REJECTED).count();
        return new ExtensionState(requested > approved + rejected, approved > 0);
    }

    private record ExtensionState(boolean pending, boolean approved) {
    }

    private void refreshStatus(Submission submission) {
        long filled = assignmentRepository.countBySubmissionIdAndStatusIn(
                submission.getId(),
                List.of(AssignmentStatus.ACTIVE, AssignmentStatus.SUBMITTED));
        if (filled >= submission.getRequiredReviewers()) {
            submission.setStatus(SubmissionStatus.ASSIGNED);
        } else if (hasDeclinedOrOverdue(submission)) {
            submission.setStatus(SubmissionStatus.SHORT);
        } else {
            submission.setStatus(SubmissionStatus.PENDING);
        }
        submissionRepository.save(submission);
    }

    private boolean hasDeclinedOrOverdue(Submission submission) {
        return assignmentRepository.countBySubmissionIdAndStatusIn(
                submission.getId(),
                List.of(AssignmentStatus.DECLINED, AssignmentStatus.OVERDUE)) > 0;
    }

    /**
     * 候选规则：启用；至少匹配一个主题；与任一作者既不是同一人（编号不相同）也不属同一机构；
     * 当前有效任务数小于上限；同一稿件此前从未分配过（含已拒绝）。
     * 排序规则：主题匹配数降序 → 当前任务数升序 → 评审人 ID 升序。
     *
     * @param strict true 时人数不足抛出 BusinessRuleException（整笔失败回滚）；
     *               false 时返回全部可用候选（用于拒绝/逾期后补选，无替补则进入缺员状态）。
     */
    private List<Reviewer> chooseReviewers(Submission submission, int needed, boolean strict) {
        Set<String> submissionTopics = Set.copyOf(submission.getTopics());
        Set<String> authorCodes = submission.getAuthors().stream()
                .map(com.chris64233.cc.peerreview.domain.Author::getCode)
                .collect(Collectors.toSet());
        Set<String> authorAffiliations = submission.getAuthors().stream()
                .map(com.chris64233.cc.peerreview.domain.Author::getAffiliation)
                .collect(Collectors.toSet());

        List<Reviewer> candidates = reviewerRepository.findEnabledByTopicIn(submissionTopics);

        // 按 ID 升序一次性对候选评审人加悲观写锁，全系统加锁顺序一致，杜绝并发分配死锁；
        // 锁内统计有效任务数，确保工作量判定不被并发事务突破。
        List<Long> candidateIds = candidates.stream().map(Reviewer::getId).sorted().toList();
        if (!candidateIds.isEmpty()) {
            reviewerRepository.findAllByIdForUpdate(candidateIds);
        }

        List<Reviewer> eligible = new ArrayList<>();
        Map<Long, Long> activeLoads = new HashMap<>();
        for (Reviewer reviewer : candidates) {
            if (!reviewer.isEnabled()) {
                continue;
            }
            if (authorCodes.contains(reviewer.getCode())) {
                continue;
            }
            if (authorAffiliations.contains(reviewer.getAffiliation())) {
                continue;
            }
            if (assignmentRepository.existsBySubmissionIdAndReviewerId(submission.getId(), reviewer.getId())) {
                continue;
            }
            long active = assignmentRepository.countActiveByReviewerId(reviewer.getId());
            activeLoads.put(reviewer.getId(), active);
            if (active >= reviewer.getMaxAssignments()) {
                continue;
            }
            eligible.add(reviewer);
        }

        eligible.sort(Comparator
                .comparingLong((Reviewer r) -> -topicMatchCount(r, submissionTopics))
                .thenComparingLong(reviewer -> activeLoads.getOrDefault(reviewer.getId(), 0L))
                .thenComparingLong(Reviewer::getId));

        if (eligible.size() < needed) {
            if (strict) {
                throw new BusinessRuleException(String.format(
                        "符合条件的评审人不足：需要 %d 人，仅有 %d 人", needed, eligible.size()));
            }
            return eligible;
        }
        return eligible.subList(0, needed);
    }

    private long topicMatchCount(Reviewer reviewer, Set<String> submissionTopics) {
        Set<String> intersection = new HashSet<>(reviewer.getTopics());
        intersection.retainAll(submissionTopics);
        return intersection.size();
    }

    private AssignmentDto toDto(Assignment assignment) {
        Reviewer reviewer = assignment.getReviewer();
        return new AssignmentDto(
                assignment.getId(),
                assignment.getSubmission().getManuscriptNo(),
                reviewer.getId(),
                reviewer.getCode(),
                reviewer.getAffiliation(),
                assignment.getStatus().name(),
                assignment.getDueAt(),
                assignment.getDeadlineBasis(),
                assignment.getSubmittedAt(),
                assignment.getOverdueReason(),
                assignment.getCreatedAt(),
                assignment.getUpdatedAt());
    }

    private AssignmentEventDto toEventDto(AssignmentEvent event) {
        return new AssignmentEventDto(
                event.getId(),
                event.getAssignmentId(),
                event.getReviewerCode(),
                event.getType().name(),
                event.getActor(),
                event.getDetail(),
                event.getCreatedAt());
    }
}
