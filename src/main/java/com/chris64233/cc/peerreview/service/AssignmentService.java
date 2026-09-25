package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.domain.Submission;
import com.chris64233.cc.peerreview.domain.SubmissionStatus;
import com.chris64233.cc.peerreview.exception.BusinessRuleException;
import com.chris64233.cc.peerreview.exception.ConflictException;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import com.chris64233.cc.peerreview.web.dto.AssignmentDto;
import com.chris64233.cc.peerreview.web.dto.SubmissionAssignmentDto;
import org.springframework.stereotype.Service;
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

    private final SubmissionRepository submissionRepository;
    private final ReviewerRepository reviewerRepository;
    private final AssignmentRepository assignmentRepository;
    private final Clock clock;

    public AssignmentService(SubmissionRepository submissionRepository,
                             ReviewerRepository reviewerRepository,
                             AssignmentRepository assignmentRepository,
                             Clock clock) {
        this.submissionRepository = submissionRepository;
        this.reviewerRepository = reviewerRepository;
        this.assignmentRepository = assignmentRepository;
        this.clock = clock;
    }

    /**
     * 自动为稿件分配评审人。仅允许对尚无任何分配记录的稿件执行；
     * 候选不足时抛出 BusinessRuleException，事务回滚，任何人的工作量都不增加。
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
            assignmentRepository.save(new Assignment(submission, reviewer, AssignmentStatus.ACTIVE, now));
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
            assignment.setUpdatedAt(clock.instant());
            assignmentRepository.save(assignment);

            // 拒绝已落库后再挑选替补，ACTIVE 计数立即释放；无替补时不抛异常，保留已有有效分配。
            List<Reviewer> replacements = chooseReviewers(submission, 1, false);
            if (!replacements.isEmpty()) {
                assignmentRepository.save(
                        new Assignment(submission, replacements.get(0), AssignmentStatus.ACTIVE, clock.instant()));
            }
        }
        refreshStatus(submission);
        return getDetail(manuscriptNo);
    }

    @Transactional(readOnly = true)
    public SubmissionAssignmentDto getDetail(String manuscriptNo) {
        Submission submission = submissionRepository.findByManuscriptNo(manuscriptNo)
                .orElseThrow(() -> new com.chris64233.cc.peerreview.exception.ResourceNotFoundException(
                        "投稿不存在: " + manuscriptNo));
        List<Assignment> assignments = assignmentRepository.findBySubmissionManuscriptNo(manuscriptNo);
        long active = assignments.stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .count();
        List<AssignmentDto> dtos = assignments.stream().map(this::toDto).toList();
        return new SubmissionAssignmentDto(
                manuscriptNo,
                submission.getStatus().name(),
                submission.getRequiredReviewers(),
                active,
                dtos);
    }

    private Submission lockSubmission(String manuscriptNo) {
        return submissionRepository.findByManuscriptNoForUpdate(manuscriptNo)
                .orElseThrow(() -> new com.chris64233.cc.peerreview.exception.ResourceNotFoundException(
                        "投稿不存在: " + manuscriptNo));
    }

    private void refreshStatus(Submission submission) {
        long active = assignmentRepository.countBySubmissionIdAndStatus(
                submission.getId(), AssignmentStatus.ACTIVE);
        if (active >= submission.getRequiredReviewers()) {
            submission.setStatus(SubmissionStatus.ASSIGNED);
        } else if (hasDeclined(submission)) {
            submission.setStatus(SubmissionStatus.SHORT);
        } else {
            submission.setStatus(SubmissionStatus.PENDING);
        }
        submissionRepository.save(submission);
    }

    private boolean hasDeclined(Submission submission) {
        return assignmentRepository.countBySubmissionIdAndStatus(
                submission.getId(), AssignmentStatus.DECLINED) > 0;
    }

    /**
     * 候选规则：启用；至少匹配一个主题；与任一作者既不是同一人（编号不相同）也不属同一机构；
     * 当前有效任务数小于上限；同一稿件此前从未分配过（含已拒绝）。
     * 排序规则：主题匹配数降序 → 当前任务数升序 → 评审人 ID 升序。
     *
     * @param strict true 时人数不足抛出 BusinessRuleException（整笔失败回滚）；
     *               false 时返回全部可用候选（用于拒绝后补选，无替补则进入缺员状态）。
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
                assignment.getCreatedAt(),
                assignment.getUpdatedAt());
    }
}
