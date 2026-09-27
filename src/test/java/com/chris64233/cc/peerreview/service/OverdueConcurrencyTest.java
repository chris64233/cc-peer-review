package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentEventType;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import com.chris64233.cc.peerreview.domain.Author;
import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.domain.Submission;
import com.chris64233.cc.peerreview.exception.ConflictException;
import com.chris64233.cc.peerreview.repository.AssignmentEventRepository;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import com.chris64233.cc.peerreview.web.dto.ExtensionApprovalRequest;
import com.chris64233.cc.peerreview.web.dto.ExtensionRequestBody;
import com.chris64233.cc.peerreview.web.dto.SubmissionAssignmentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评审意见提交、延期审批与逾期扫描并发时，同一任务只能产生一个终态。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:peerreview-overdue-concurrency-test;DB_CLOSE_DELAY=-1",
        "review.deadline.scan-enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OverdueConcurrencyTest {

    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private OverdueScanService overdueScanService;
    @Autowired
    private ReviewerRepository reviewerRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private AssignmentRepository assignmentRepository;
    @Autowired
    private AssignmentEventRepository eventRepository;

    @BeforeEach
    void clean() {
        eventRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        submissionRepository.deleteAllInBatch();
        reviewerRepository.deleteAllInBatch();
    }

    @Test
    void submitAndOverdueScanRaceYieldsExactlyOneTerminalState() throws Exception {
        reviewerRepository.saveAndFlush(new Reviewer("R1", "MIT", Set.of("ML"), 5, true));
        reviewerRepository.saveAndFlush(new Reviewer("R2", "Stanford", Set.of("ML"), 5, true));
        submissionRepository.saveAndFlush(new Submission(
                "MS-C1", Set.of("ML"), Set.of(new Author("A1", "Berkeley")), 1));
        assignmentService.autoAssign("MS-C1");
        forceOverdue("MS-C1", "R1");

        AtomicReference<Throwable> submitError = new AtomicReference<>();
        runInParallel(
                () -> {
                    try {
                        assignmentService.submitReview("MS-C1", "R1", "踩点提交");
                    } catch (ConflictException e) {
                        submitError.set(e);
                    }
                },
                overdueScanService::scanOnce);

        SubmissionAssignmentDto detail = assignmentService.getDetail("MS-C1");
        String r1Status = statusOf(detail, "R1");
        if (submitError.get() == null) {
            // 提交先完成：任务 SUBMITTED 终态，扫描不得改派，不产生替补。
            assertThat(r1Status).isEqualTo(AssignmentStatus.SUBMITTED.name());
            assertThat(detail.assignments()).hasSize(1);
            assertThat(eventTypes(detail)).doesNotContain(AssignmentEventType.OVERDUE.name());
        } else {
            // 扫描先完成：任务 OVERDUE 终态，迟到提交被拒且留痕，替补已原子补入且不重复占负载。
            assertThat(r1Status).isEqualTo(AssignmentStatus.OVERDUE.name());
            assertThat(detail.assignments()).hasSize(2);
            assertThat(statusOf(detail, "R2")).isEqualTo(AssignmentStatus.ACTIVE.name());
            assertThat(eventTypes(detail)).contains(
                    AssignmentEventType.OVERDUE.name(), AssignmentEventType.LATE_SUBMITTED.name());
            assertThat(activeLoad("R1")).isZero();
            assertThat(activeLoad("R2")).isEqualTo(1);
        }
    }

    @Test
    void extensionApprovalAndOverdueScanRaceYieldsExactlyOneOutcome() throws Exception {
        reviewerRepository.saveAndFlush(new Reviewer("R1", "MIT", Set.of("ML"), 5, true));
        reviewerRepository.saveAndFlush(new Reviewer("R2", "Stanford", Set.of("ML"), 5, true));
        submissionRepository.saveAndFlush(new Submission(
                "MS-C2", Set.of("ML"), Set.of(new Author("A1", "Berkeley")), 1));
        assignmentService.autoAssign("MS-C2");
        assignmentService.requestExtension("MS-C2", "R1", new ExtensionRequestBody(7, null, "R1"));
        forceOverdue("MS-C2", "R1");

        AtomicReference<Throwable> approveError = new AtomicReference<>();
        runInParallel(
                () -> {
                    try {
                        assignmentService.approveExtension("MS-C2", "R1",
                                new ExtensionApprovalRequest(7, "editor-1"));
                    } catch (ConflictException e) {
                        approveError.set(e);
                    }
                },
                overdueScanService::scanOnce);

        SubmissionAssignmentDto detail = assignmentService.getDetail("MS-C2");
        if (approveError.get() == null) {
            // 批准先生效：截止期推后到未来，任务保持 ACTIVE，扫描跳过。
            assertThat(statusOf(detail, "R1")).isEqualTo(AssignmentStatus.ACTIVE.name());
            assertThat(dueAtOf(detail, "R1")).isAfter(Instant.now());
            assertThat(detail.assignments()).hasSize(1);
            assertThat(eventTypes(detail)).contains(AssignmentEventType.EXTENSION_APPROVED.name())
                    .doesNotContain(AssignmentEventType.OVERDUE.name());
        } else {
            // 扫描先完成：任务 OVERDUE 终态，批准被拒，只能有这一个终态。
            assertThat(statusOf(detail, "R1")).isEqualTo(AssignmentStatus.OVERDUE.name());
            assertThat(eventTypes(detail)).contains(AssignmentEventType.OVERDUE.name())
                    .doesNotContain(AssignmentEventType.EXTENSION_APPROVED.name());
        }
    }

    private void runInParallel(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = List.of(
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    first.run();
                    return null;
                }),
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    second.run();
                    return null;
                }));
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();
    }

    private String statusOf(SubmissionAssignmentDto detail, String reviewerCode) {
        return detail.assignments().stream()
                .filter(a -> a.reviewerCode().equals(reviewerCode))
                .findFirst().orElseThrow().status();
    }

    private Instant dueAtOf(SubmissionAssignmentDto detail, String reviewerCode) {
        return detail.assignments().stream()
                .filter(a -> a.reviewerCode().equals(reviewerCode))
                .findFirst().orElseThrow().dueAt();
    }

    private List<String> eventTypes(SubmissionAssignmentDto detail) {
        return detail.events().stream().map(e -> e.type()).toList();
    }

    private long activeLoad(String reviewerCode) {
        return assignmentRepository.countActiveByReviewerId(
                reviewerRepository.findByCode(reviewerCode).orElseThrow().getId());
    }

    private void forceOverdue(String manuscriptNo, String reviewerCode) {
        Assignment assignment = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode(manuscriptNo, reviewerCode).get(0);
        assignment.setDueAt(Instant.now().minusSeconds(3600));
        assignmentRepository.saveAndFlush(assignment);
    }
}
