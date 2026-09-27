package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import com.chris64233.cc.peerreview.domain.Author;
import com.chris64233.cc.peerreview.domain.Review;
import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.domain.Submission;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ExtensionEventRepository;
import com.chris64233.cc.peerreview.repository.ReviewRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评审意见提交与逾期扫描并发时，全系统只会产生一个终态：
 * 要么 COMPLETED（无改派、意见准时），要么 OVERDUE（负载释放、替补接管、意见迟到）。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:peerreview-terminal-test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TerminalStateConcurrencyTest {

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
    void submitAndScanRaceProducesExactlyOneTerminalState() throws Exception {
        reviewerRepository.saveAndFlush(new Reviewer("R1", "MIT", Set.of("ML", "NLP"), 5, true));
        reviewerRepository.saveAndFlush(new Reviewer("R2", "Stanford", Set.of("ML"), 5, true));
        submissionRepository.saveAndFlush(new Submission(
                "MS-RACE", Set.of("ML"), Set.of(new Author("A1", "Berkeley")), 1));
        assignmentService.autoAssign("MS-RACE");
        backdateDueAt("MS-RACE", "R1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> submitFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            reviewService.submit("MS-RACE", "R1", "与扫描并发的评审意见");
            return null;
        });
        Future<?> scanFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            overdueScanService.scanOverdue();
            return null;
        });
        ready.await();
        start.countDown();
        submitFuture.get();
        scanFuture.get();
        pool.shutdown();

        Assignment oldTask = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode("MS-RACE", "R1").get(0);
        Review review = reviewRepository.findByAssignmentId(oldTask.getId()).orElseThrow();
        long r1Active = assignmentRepository.countActiveByReviewerId(
                reviewerRepository.findByCode("R1").orElseThrow().getId());

        if (oldTask.getStatus() == AssignmentStatus.COMPLETED) {
            // 提交获胜：无改派、无迟到、旧任务不再占用负载。
            assertThat(review.isLate()).isFalse();
            assertThat(assignmentRepository.count()).isEqualTo(1);
        } else {
            // 扫描获胜：恰好一个终态 OVERDUE，替补接管，迟到意见不恢复任务。
            assertThat(oldTask.getStatus()).isEqualTo(AssignmentStatus.OVERDUE);
            assertThat(review.isLate()).isTrue();
            assertThat(assignmentRepository.count()).isEqualTo(2);
            assertThat(assignmentRepository
                    .findBySubmissionManuscriptNoAndReviewerCode("MS-RACE", "R2").get(0)
                    .getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        }
        // 无论谁获胜，旧任务都不会再次占用负载。
        assertThat(r1Active).isZero();
    }

    @Test
    void concurrentScansCreateAtMostOneReplacement() throws Exception {
        reviewerRepository.saveAndFlush(new Reviewer("R1", "MIT", Set.of("ML", "NLP"), 5, true));
        reviewerRepository.saveAndFlush(new Reviewer("R2", "Stanford", Set.of("ML"), 5, true));
        submissionRepository.saveAndFlush(new Submission(
                "MS-DS", Set.of("ML"), Set.of(new Author("A1", "Berkeley")), 1));
        assignmentService.autoAssign("MS-DS");
        backdateDueAt("MS-DS", "R1");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                overdueScanService.scanOverdue();
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        // 多个扫描实例并发：旧任务只逾期一次，替补只创建一个，旧任务负载只释放一次。
        assertThat(assignmentRepository.count()).isEqualTo(2);
        Assignment oldTask = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode("MS-DS", "R1").get(0);
        assertThat(oldTask.getStatus()).isEqualTo(AssignmentStatus.OVERDUE);
        assertThat(assignmentRepository.countActiveByReviewerId(
                reviewerRepository.findByCode("R1").orElseThrow().getId())).isZero();
        assertThat(assignmentRepository.countActiveByReviewerId(
                reviewerRepository.findByCode("R2").orElseThrow().getId())).isEqualTo(1);
    }

    private void backdateDueAt(String manuscriptNo, String reviewerCode) {
        Assignment assignment = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode(manuscriptNo, reviewerCode).get(0);
        assignment.setDueAt(Instant.now().minus(1, ChronoUnit.DAYS));
        assignmentRepository.save(assignment);
    }
}
