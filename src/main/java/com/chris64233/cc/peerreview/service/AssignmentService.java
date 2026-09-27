package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import com.chris64233.cc.peerreview.domain.ExtensionEvent;
import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.domain.Submission;
import com.chris64233.cc.peerreview.domain.SubmissionStatus;
import com.chris64233.cc.peerreview.exception.BusinessRuleException;
import com.chris64233.cc.peerreview.exception.ConflictException;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ExtensionEventRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import com.chris64233.cc.peerreview.web.dto.AssignmentDto;
import com.chris64233.cc.peerreview.web.dto.ExtensionEventDto;
import com.chris64233.cc.peerreview.web.dto.ReassignmentDto;
import com.chris64233.cc.peerreview.web.dto.SubmissionAssignmentDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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

    /** 逾期任务的终态原因说明，随任务保存并在查询中展示。 */
    public static final String OVERDUE_REASON = "截止期到达未提交评审意见，已逾期改派";

    private final SubmissionRepository submissionRepository;
    private final ReviewerRepository reviewerRepository;
    private final AssignmentRepository assignmentRepository;
    private final ExtensionEventRepository extensionEventRepository;
    private final DeadlinePolicy deadlinePolicy;
    private final Clock clock;

    public AssignmentService(SubmissionRepository submissionRepository,
                             ReviewerRepository reviewerRepository,
                             AssignmentRepository assignmentRepository,
                             ExtensionEventRepository extensionEventRepository,
                             DeadlinePolicy deadlinePolicy,
                             Clock clock) {
        this.submissionRepository = submissionRepository;
        this.reviewerRepository = reviewerRepository;
        this.assignmentRepository = assignmentRepository;
        this.extensionEventRepository = extensionEventRepository;
        this.deadlinePolicy = deadlinePolicy;
        this.clock = clock;
    }

    /**
     * 自动为稿件分配评审人。仅允许对尚无任何分配记录的稿件执行；
     * 候选不足时抛出 BusinessRuleException，事务回滚，任何人的工作量都不增加。
     * 每条任务按“稿件轮次 + 评审人规则”计算截止时间，并把计算依据快照保存到任务上。
     */
    @Transactional
    public SubmissionAssignmentDto autoAssign(String manuscriptNo) {
        Submission submission = lockSubmission(manuscriptNo);

        if (!assignmentRepository.findBySubmissionManuscriptNo(manuscriptNo).isEmpty()) {
            throw new ConflictException("稿件已存在分配记录，不能重复自动分配: " + manuscriptNo);
        }

        List<Reviewer> chosen = chooseReviewers(submission, submission.getRequiredReviewers(), true);
        Instant now = clock.instant();
        for (Reviewer reviewer : chosen) {
            Assignment assignment = new Assignment(submission, reviewer, AssignmentStatus.ACTIVE, now);
            applyDeadline(assignment, submission, reviewer, now);
            assignmentRepository.save(assignment);
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

        List<Assignment> targets = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode(manuscriptNo, reviewerCode);
        if (targets.isEmpty()) {
            throw new BusinessRuleException(
                    "该评审人与此稿件之间不存在分配记录: " + reviewerCode);
        }

        Assignment assignment = targets.get(0);
        if (assignment.getStatus() == AssignmentStatus.ACTIVE) {
            assignment.setStatus(AssignmentStatus.DECLINED);
            assignment.setStatusReason("评审人拒绝任务");
            assignment.setUpdatedAt(clock.instant());
            assignmentRepository.save(assignment);

            // 拒绝已落库后再挑选替补，ACTIVE 计数立即释放；无替补时不抛异常，保留已有有效分配。
            List<Reviewer> replacements = chooseReviewers(submission, 1, false);
            if (!replacements.isEmpty()) {
                Assignment replacement = new Assignment(
                        submission, replacements.get(0), AssignmentStatus.ACTIVE, clock.instant());
                replacement.setReplacesAssignment(assignment);
                applyDeadline(replacement, submission, replacements.get(0), clock.instant());
                assignmentRepository.save(replacement);
            }
        }
        refreshStatus(submission);
        return getDetail(manuscriptNo);
    }

    /**
     * 处理单条逾期任务（逾期扫描的基本单元，独立事务，失败不影响同批次其他任务）：
     * 先将任务以条件更新（CAS）原子转为 OVERDUE 终态并释放负载，再按既有
     * 利益冲突/主题能力/工作量规则补选替补。与评审意见提交、延期审批并发时，
     * CAS 保证全系统只产生一个终态；已提交或已被其他扫描实例处理的任务返回 SKIPPED。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OverdueOutcome processOverdue(Long assignmentId) {
        Assignment assignment = assignmentRepository.findById(assignmentId).orElse(null);
        if (assignment == null || assignment.getStatus() != AssignmentStatus.ACTIVE) {
            return OverdueOutcome.SKIPPED;
        }
        // 加锁顺序与分配/拒绝一致：先锁稿件行，再改任务，避免与并发事务互相等待。
        Submission submission = lockSubmission(assignment.getSubmission().getManuscriptNo());

        Instant now = clock.instant();
        int updated = assignmentRepository.markOverdueIfActive(assignmentId, OVERDUE_REASON, now);
        if (updated == 0) {
            // 已被并发提交/审批改变状态或截止期，本次不占用负载、不产生任何副作用。
            return OverdueOutcome.SKIPPED;
        }

        List<Reviewer> replacements = chooseReviewers(submission, 1, false);
        if (replacements.isEmpty()) {
            refreshStatus(submission);
            return OverdueOutcome.OVERDUE_NO_REPLACEMENT;
        }
        Assignment replacement = new Assignment(submission, replacements.get(0), AssignmentStatus.ACTIVE, now);
        replacement.setReplacesAssignment(assignment);
        applyDeadline(replacement, submission, replacements.get(0), now);
        assignmentRepository.save(replacement);
        refreshStatus(submission);
        return OverdueOutcome.OVERDUE_REASSIGNED;
    }

    /**
     * 投稿分配详情：当前有效评审人、历次改派链、每条任务的截止期与延期事件、逾期原因。
     */
    @Transactional(readOnly = true)
    public SubmissionAssignmentDto getDetail(String manuscriptNo) {
        Submission submission = submissionRepository.findByManuscriptNo(manuscriptNo)
                .orElseThrow(() -> new com.chris64233.cc.peerreview.exception.ResourceNotFoundException(
                        "投稿不存在: " + manuscriptNo));
        List<Assignment> assignments = assignmentRepository.findBySubmissionManuscriptNo(manuscriptNo);
        long active = assignments.stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .count();

        Map<Long, List<ExtensionEventDto>> eventsByAssignment = loadExtensionEvents(assignments);
        List<AssignmentDto> dtos = assignments.stream()
                .map(a -> toDto(a, eventsByAssignment.getOrDefault(a.getId(), List.of())))
                .toList();
        List<ReassignmentDto> reassignments = assignments.stream()
                .filter(a -> a.getReplacesAssignment() != null)
                .map(AssignmentService::toReassignmentDto)
                .toList();
        return new SubmissionAssignmentDto(
                manuscriptNo,
                submission.getStatus().name(),
                submission.getRequiredReviewers(),
                active,
                dtos,
                reassignments);
    }

    private Map<Long, List<ExtensionEventDto>> loadExtensionEvents(List<Assignment> assignments) {
        List<Long> ids = assignments.stream().map(Assignment::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ExtensionEventDto>> result = new HashMap<>();
        for (ExtensionEvent event : extensionEventRepository.findByAssignmentIdInOrderByIdAsc(ids)) {
            result.computeIfAbsent(event.getAssignment().getId(), k -> new ArrayList<>())
                    .add(toEventDto(event));
        }
        return result;
    }

    private static ReassignmentDto toReassignmentDto(Assignment replacement) {
        Assignment previous = replacement.getReplacesAssignment();
        String reason = previous.getStatusReason() != null
                ? previous.getStatusReason()
                : previous.getStatus().name();
        return new ReassignmentDto(
                previous.getId(),
                previous.getReviewer().getCode(),
                replacement.getId(),
                replacement.getReviewer().getCode(),
                reason,
                replacement.getCreatedAt());
    }

    private void applyDeadline(Assignment assignment, Submission submission, Reviewer reviewer, Instant now) {
        DeadlinePolicy.DeadlineComputation computation = deadlinePolicy.compute(submission, reviewer, now);
        assignment.setReviewRound(computation.reviewRound());
        assignment.setRoundDays(computation.roundDays());
        assignment.setReviewerDays(computation.reviewerDays());
        assignment.setExtensionDays(0);
        assignment.setDueAt(computation.dueAt());
        assignment.setDeadlineBasis(computation.basis());
    }

    private Submission lockSubmission(String manuscriptNo) {
        return submissionRepository.findByManuscriptNoForUpdate(manuscriptNo)
                .orElseThrow(() -> new com.chris64233.cc.peerreview.exception.ResourceNotFoundException(
                        "投稿不存在: " + manuscriptNo));
    }

    /**
     * 有效人数 = ACTIVE + 已提交 COMPLETED；不足时若曾发生拒绝或逾期流失则进入缺员。
     */
    void refreshStatus(Submission submission) {
        long fulfilled = assignmentRepository.countBySubmissionIdAndStatus(
                submission.getId(), AssignmentStatus.ACTIVE)
                + assignmentRepository.countBySubmissionIdAndStatus(
                submission.getId(), AssignmentStatus.COMPLETED);
        if (fulfilled >= submission.getRequiredReviewers()) {
            submission.setStatus(SubmissionStatus.ASSIGNED);
        } else if (hasLost(submission)) {
            submission.setStatus(SubmissionStatus.SHORT);
        } else {
            submission.setStatus(SubmissionStatus.PENDING);
        }
        submissionRepository.save(submission);
    }

    private boolean hasLost(Submission submission) {
        return assignmentRepository.countBySubmissionIdAndStatus(
                submission.getId(), AssignmentStatus.DECLINED) > 0
                || assignmentRepository.countBySubmissionIdAndStatus(
                submission.getId(), AssignmentStatus.OVERDUE) > 0;
    }

    /**
     * 候选规则：启用；至少匹配一个主题；与任一作者既不是同一人（编号不相同）也不属同一机构；
     * 当前有效任务数小于上限；同一稿件此前从未分配过（含已拒绝、已逾期）。
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

    private AssignmentDto toDto(Assignment assignment, List<ExtensionEventDto> events) {
        Reviewer reviewer = assignment.getReviewer();
        return new AssignmentDto(
                assignment.getId(),
                assignment.getSubmission().getManuscriptNo(),
                reviewer.getId(),
                reviewer.getCode(),
                reviewer.getAffiliation(),
                assignment.getStatus().name(),
                assignment.getCreatedAt(),
                assignment.getUpdatedAt(),
                assignment.getDueAt(),
                assignment.getReviewRound(),
                assignment.getExtensionDays(),
                assignment.getDeadlineBasis(),
                assignment.getStatusReason(),
                assignment.getReplacesAssignment() != null
                        ? assignment.getReplacesAssignment().getId() : null,
                events);
    }

    static ExtensionEventDto toEventDto(ExtensionEvent event) {
        return new ExtensionEventDto(
                event.getId(),
                event.getAssignment().getId(),
                event.getType().name(),
                event.getDays(),
                event.getReason(),
                event.getActor(),
                event.getPreviousDueAt(),
                event.getResultingDueAt(),
                event.getCreatedAt());
    }
}
