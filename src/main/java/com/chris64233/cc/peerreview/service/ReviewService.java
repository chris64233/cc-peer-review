package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import com.chris64233.cc.peerreview.domain.Review;
import com.chris64233.cc.peerreview.exception.BusinessRuleException;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ReviewRepository;
import com.chris64233.cc.peerreview.web.dto.ReviewDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 评审意见提交。任务状态通过条件更新（CAS）从 ACTIVE 原子转为 COMPLETED，
 * 与逾期扫描并发时全系统只会产生一个终态：
 * 提交获胜则任务完成；扫描获胜则意见按迟到存档（late=true），不得恢复任务。
 */
@Service
public class ReviewService {

    private final AssignmentRepository assignmentRepository;
    private final ReviewRepository reviewRepository;
    private final AssignmentService assignmentService;
    private final Clock clock;

    public ReviewService(AssignmentRepository assignmentRepository,
                         ReviewRepository reviewRepository,
                         AssignmentService assignmentService,
                         Clock clock) {
        this.assignmentRepository = assignmentRepository;
        this.reviewRepository = reviewRepository;
        this.assignmentService = assignmentService;
        this.clock = clock;
    }

    /**
     * 提交评审意见。幂等：同一任务重复提交直接返回已有意见，不产生新记录。
     */
    @Transactional
    public ReviewDto submit(String manuscriptNo, String reviewerCode, String content) {
        List<Assignment> found = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode(manuscriptNo, reviewerCode);
        if (found.isEmpty()) {
            throw new BusinessRuleException("该评审人与此稿件之间不存在分配记录: " + reviewerCode);
        }
        Assignment assignment = found.get(0);

        Optional<Review> existing = reviewRepository.findByAssignmentId(assignment.getId());
        if (existing.isPresent()) {
            return toDto(existing.get());
        }

        Instant now = clock.instant();
        // 单语句条件更新：仅 ACTIVE 任务能被本次提交终结；并发逾期扫描获胜时返回 0。
        boolean completed = assignmentRepository.markCompletedIfActive(assignment.getId(), now) == 1;
        Review review = reviewRepository.saveAndFlush(new Review(assignment, content, !completed, now));

        if (completed) {
            assignmentService.refreshStatus(assignment.getSubmission());
        }
        return toDto(review);
    }

    @Transactional(readOnly = true)
    public Optional<ReviewDto> find(String manuscriptNo, String reviewerCode) {
        return assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode(manuscriptNo, reviewerCode)
                .stream()
                .findFirst()
                .flatMap(assignment -> reviewRepository.findByAssignmentId(assignment.getId()))
                .map(this::toDto);
    }

    private ReviewDto toDto(Review review) {
        Assignment assignment = review.getAssignment();
        return new ReviewDto(
                review.getId(),
                assignment.getId(),
                assignment.getSubmission().getManuscriptNo(),
                assignment.getReviewer().getCode(),
                review.isLate(),
                review.getContent(),
                review.getSubmittedAt());
    }
}
