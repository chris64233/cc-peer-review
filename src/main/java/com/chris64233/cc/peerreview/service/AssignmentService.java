package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import com.chris64233.cc.peerreview.domain.Author;
import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.domain.Submission;
import com.chris64233.cc.peerreview.domain.SubmissionStatus;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import com.chris64233.cc.peerreview.web.dto.AssignmentView;
import com.chris64233.cc.peerreview.web.dto.ReviewerLoadView;
import com.chris64233.cc.peerreview.web.dto.SubmissionAssignmentDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AssignmentService {

    private final SubmissionRepository submissionRepository;
    private final ReviewerRepository reviewerRepository;
    private final AssignmentRepository assignmentRepository;

    public AssignmentService(SubmissionRepository submissionRepository,
                             ReviewerRepository reviewerRepository,
                             AssignmentRepository assignmentRepository) {
        this.submissionRepository = submissionRepository;
        this.reviewerRepository = reviewerRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Transactional
    public SubmissionAssignmentDetail assign(Long submissionId) {
        Submission submission = findSubmission(submissionId);
        long activeCount = assignmentRepository.findBySubmissionIdOrderById(submissionId).stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .count();
        if (activeCount > 0) {
            throw new AssignmentException("稿件 " + submission.getManuscriptNo() + " 已存在有效分配，不能重复自动分配");
        }

        List<Candidate> eligible = eligibleCandidates(submission);
        List<Reviewer> chosen = new ArrayList<>();
        for (Candidate candidate : eligible) {
            if (chosen.size() >= submission.getRequiredReviewers()) {
                break;
            }
            chosen.add(candidate.reviewer());
        }
        if (chosen.size() < submission.getRequiredReviewers()) {
            throw new AssignmentException("合格评审人不足：需要 " + submission.getRequiredReviewers()
                    + " 人，仅找到 " + chosen.size() + " 人，本次分配整体失败");
        }

        for (Reviewer reviewer : chosen) {
            assignmentRepository.save(new Assignment(submission, reviewer));
        }
        submission.setStatus(SubmissionStatus.ASSIGNED);
        return detail(submission);
    }

    @Transactional
    public SubmissionAssignmentDetail decline(Long submissionId, Long reviewerId) {
        Submission submission = findSubmission(submissionId);
        Assignment assignment = assignmentRepository
                .findBySubmissionIdAndReviewerId(submissionId, reviewerId)
                .orElseThrow(() -> new NotFoundException(
                        "稿件 " + submissionId + " 不存在评审人 " + reviewerId + " 的分配记录"));
        if (assignment.getStatus() == AssignmentStatus.DECLINED) {
            return detail(submission);
        }
        assignment.decline();

        List<Candidate> eligible = eligibleCandidates(submission);
        if (!eligible.isEmpty()) {
            assignmentRepository.save(new Assignment(submission, eligible.getFirst().reviewer()));
        } else {
            submission.setStatus(SubmissionStatus.UNDERSTAFFED);
        }
        return detail(submission);
    }

    @Transactional(readOnly = true)
    public SubmissionAssignmentDetail getSubmissionDetail(Long submissionId) {
        return detail(findSubmission(submissionId));
    }

    @Transactional(readOnly = true)
    public ReviewerLoadView getReviewerLoad(Long reviewerId) {
        Reviewer reviewer = reviewerRepository.findById(reviewerId)
                .orElseThrow(() -> new NotFoundException("评审人不存在: " + reviewerId));
        long active = assignmentRepository.countByReviewerIdAndStatus(reviewerId, AssignmentStatus.ACTIVE);
        return new ReviewerLoadView(reviewer.getId(), reviewer.getReviewerNo(), reviewer.getName(),
                active, reviewer.getMaxConcurrent(), reviewer.getMaxConcurrent() - active);
    }

    private List<Candidate> eligibleCandidates(Submission submission) {
        List<Reviewer> reviewers = reviewerRepository.lockAllActive();
        Set<Long> excludedIds = assignmentRepository.findReviewerIdsBySubmissionId(submission.getId());
        Set<String> authorNames = submission.getAuthors().stream()
                .map(Author::getName)
                .collect(Collectors.toSet());
        Set<String> authorAffiliations = submission.getAuthors().stream()
                .map(Author::getAffiliation)
                .collect(Collectors.toSet());

        List<Candidate> eligible = new ArrayList<>();
        for (Reviewer reviewer : reviewers) {
            if (excludedIds.contains(reviewer.getId())) {
                continue;
            }
            if (authorNames.contains(reviewer.getName())) {
                continue;
            }
            if (authorAffiliations.contains(reviewer.getAffiliation())) {
                continue;
            }
            Set<String> matched = new HashSet<>(reviewer.getTopics());
            matched.retainAll(submission.getTopics());
            if (matched.isEmpty()) {
                continue;
            }
            long load = assignmentRepository.countByReviewerIdAndStatus(
                    reviewer.getId(), AssignmentStatus.ACTIVE);
            if (load >= reviewer.getMaxConcurrent()) {
                continue;
            }
            eligible.add(new Candidate(reviewer, matched.size(), load));
        }
        eligible.sort(Comparator.comparingInt(Candidate::matchCount).reversed()
                .thenComparingLong(Candidate::load)
                .thenComparing(c -> c.reviewer().getId()));
        return eligible;
    }

    private Submission findSubmission(Long submissionId) {
        return submissionRepository.findById(submissionId)
                .orElseThrow(() -> new NotFoundException("稿件不存在: " + submissionId));
    }

    private SubmissionAssignmentDetail detail(Submission submission) {
        List<AssignmentView> views = assignmentRepository
                .findBySubmissionIdOrderById(submission.getId()).stream()
                .map(a -> new AssignmentView(a.getId(), a.getReviewer().getId(),
                        a.getReviewer().getReviewerNo(), a.getReviewer().getName(),
                        a.getStatus(), a.getCreatedAt()))
                .toList();
        long activeCount = views.stream().filter(v -> v.status() == AssignmentStatus.ACTIVE).count();
        return new SubmissionAssignmentDetail(submission.getId(), submission.getManuscriptNo(),
                submission.getStatus(), submission.getRequiredReviewers(), activeCount, views);
    }

    private record Candidate(Reviewer reviewer, int matchCount, long load) {
    }
}
