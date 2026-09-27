package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import com.chris64233.cc.peerreview.domain.Author;
import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.domain.Submission;
import com.chris64233.cc.peerreview.domain.SubmissionStatus;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ExtensionEventRepository;
import com.chris64233.cc.peerreview.repository.ReviewRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import com.chris64233.cc.peerreview.web.dto.AssignmentDto;
import com.chris64233.cc.peerreview.web.dto.ReviewDto;
import com.chris64233.cc.peerreview.web.dto.ScanResultDto;
import com.chris64233.cc.peerreview.web.dto.SubmissionAssignmentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:peerreview-overdue-test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OverdueScanServiceTest {

    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private OverdueScanService overdueScanService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private ReviewerRepository reviewerRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private AssignmentRepository assignmentRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ExtensionEventRepository extensionEventRepository;

    @BeforeEach
    void clean() {
        extensionEventRepository.deleteAllInBatch();
        reviewRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        submissionRepository.deleteAllInBatch();
        reviewerRepository.deleteAllInBatch();
    }

    @Test
    void overdueScanReleasesLoadAndReassignsAtomically() {
        saveReviewer("R1", "MIT", Set.of("ML", "NLP"), 5);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5);
        saveSubmission("MS-OD1", 1);
        assignmentService.autoAssign("MS-OD1");
        Long r1Id = reviewerRepository.findByCode("R1").orElseThrow().getId();
        Long r2Id = reviewerRepository.findByCode("R2").orElseThrow().getId();
        assertThat(assignmentRepository.countActiveByReviewerId(r1Id)).isEqualTo(1);

        backdateDueAt("MS-OD1", "R1");
        ScanResultDto result = overdueScanService.scanOverdue();

        assertThat(result.dueFound()).isEqualTo(1);
        assertThat(result.overdueMarked()).isEqualTo(1);
        assertThat(result.reassigned()).isEqualTo(1);

        // 原任务进入 OVERDUE 终态并释放负载；替补任务按相同规则原子创建。
        assertThat(assignmentRepository.countActiveByReviewerId(r1Id)).isZero();
        assertThat(assignmentRepository.countActiveByReviewerId(r2Id)).isEqualTo(1);

        SubmissionAssignmentDto detail = assignmentService.getDetail("MS-OD1");
        assertThat(detail.submissionStatus()).isEqualTo(SubmissionStatus.ASSIGNED.name());
        AssignmentDto oldTask = detail.assignments().stream()
                .filter(a -> a.reviewerCode().equals("R1")).findFirst().orElseThrow();
        AssignmentDto newTask = detail.assignments().stream()
                .filter(a -> a.reviewerCode().equals("R2")).findFirst().orElseThrow();
        assertThat(oldTask.status()).isEqualTo(AssignmentStatus.OVERDUE.name());
        assertThat(oldTask.statusReason()).contains("逾期");
        assertThat(newTask.status()).isEqualTo(AssignmentStatus.ACTIVE.name());
        assertThat(newTask.replacesAssignmentId()).isEqualTo(oldTask.assignmentId());
        assertThat(newTask.dueAt()).isAfter(Instant.now());

        // 查询展示历次改派及原因。
        assertThat(detail.reassignments()).hasSize(1);
        assertThat(detail.reassignments().get(0).fromReviewerCode()).isEqualTo("R1");
        assertThat(detail.reassignments().get(0).toReviewerCode()).isEqualTo("R2");
        assertThat(detail.reassignments().get(0).reason()).contains("逾期");
    }

    @Test
    void overdueScanWithoutReplacementLeavesSubmissionShort() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5);
        saveSubmission("MS-OD2", 1);
        assignmentService.autoAssign("MS-OD2");

        backdateDueAt("MS-OD2", "R1");
        ScanResultDto result = overdueScanService.scanOverdue();

        assertThat(result.overdueMarked()).isEqualTo(1);
        assertThat(result.reassigned()).isZero();
        SubmissionAssignmentDto detail = assignmentService.getDetail("MS-OD2");
        assertThat(detail.submissionStatus()).isEqualTo(SubmissionStatus.SHORT.name());
        assertThat(detail.activeReviewers()).isZero();
    }

    @Test
    void scanIsIdempotentAndLateReviewDoesNotReviveTask() {
        saveReviewer("R1", "MIT", Set.of("ML", "NLP"), 5);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5);
        saveSubmission("MS-OD3", 1);
        assignmentService.autoAssign("MS-OD3");
        backdateDueAt("MS-OD3", "R1");

        overdueScanService.scanOverdue();
        long assignmentsAfterFirst = assignmentRepository.count();

        // 重复扫描幂等：无新增记录、无状态变化。
        ScanResultDto second = overdueScanService.scanOverdue();
        assertThat(second.dueFound()).isZero();
        assertThat(second.overdueMarked()).isZero();
        assertThat(assignmentRepository.count()).isEqualTo(assignmentsAfterFirst);

        // 原评审人迟交意见：仅存档为 late，任务保持 OVERDUE，替补不受影响。
        ReviewDto late = reviewService.submit("MS-OD3", "R1", "迟到的评审意见");
        assertThat(late.late()).isTrue();

        SubmissionAssignmentDto detail = assignmentService.getDetail("MS-OD3");
        AssignmentDto oldTask = detail.assignments().stream()
                .filter(a -> a.reviewerCode().equals("R1")).findFirst().orElseThrow();
        assertThat(oldTask.status()).isEqualTo(AssignmentStatus.OVERDUE.name());
        assertThat(detail.activeReviewers()).isEqualTo(1);
        Long r1Id = reviewerRepository.findByCode("R1").orElseThrow().getId();
        assertThat(assignmentRepository.countActiveByReviewerId(r1Id)).isZero();

        // 迟到意见同样幂等：重复提交返回原记录。
        ReviewDto again = reviewService.submit("MS-OD3", "R1", "重复提交");
        assertThat(again.reviewId()).isEqualTo(late.reviewId());
        assertThat(reviewRepository.count()).isEqualTo(1);
    }

    @Test
    void submittedTaskIsCompletedAndNeverScannedOrReassigned() {
        saveReviewer("R1", "MIT", Set.of("ML", "NLP"), 5);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5);
        saveSubmission("MS-OD4", 1);
        assignmentService.autoAssign("MS-OD4");

        // 按期提交：任务转 COMPLETED，不再占用负载，也不再被扫描或改派。
        ReviewDto review = reviewService.submit("MS-OD4", "R1", "按期提交的评审意见");
        assertThat(review.late()).isFalse();
        Assignment completed = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode("MS-OD4", "R1").get(0);
        assertThat(completed.getStatus()).isEqualTo(AssignmentStatus.COMPLETED);

        // 即使把截止期改到过去，已提交任务也不会被扫描改派。
        backdateDueAt("MS-OD4", "R1");
        ScanResultDto result = overdueScanService.scanOverdue();
        assertThat(result.dueFound()).isZero();
        assertThat(assignmentRepository.count()).isEqualTo(1);

        // 重复提交幂等返回原意见。
        ReviewDto duplicate = reviewService.submit("MS-OD4", "R1", "重复内容");
        assertThat(duplicate.reviewId()).isEqualTo(review.reviewId());
        assertThat(reviewRepository.count()).isEqualTo(1);
    }

    private void backdateDueAt(String manuscriptNo, String reviewerCode) {
        Assignment assignment = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode(manuscriptNo, reviewerCode).get(0);
        assignment.setDueAt(Instant.now().minus(1, ChronoUnit.DAYS));
        assignmentRepository.save(assignment);
    }

    private void saveReviewer(String code, String affiliation, Set<String> topics, int max) {
        reviewerRepository.save(new Reviewer(code, affiliation, topics, max, true));
    }

    private void saveSubmission(String manuscriptNo, int reviewRound) {
        Submission submission = new Submission(
                manuscriptNo, Set.of("ML"), Set.of(new Author("A1", "Berkeley")), 1);
        submission.setReviewRound(reviewRound);
        submissionRepository.save(submission);
    }
}
