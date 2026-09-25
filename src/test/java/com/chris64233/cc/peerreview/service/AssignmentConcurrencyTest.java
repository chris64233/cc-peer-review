package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Author;
import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.domain.Submission;
import com.chris64233.cc.peerreview.exception.BusinessRuleException;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:peerreview-concurrency-test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AssignmentConcurrencyTest {

    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private ReviewerRepository reviewerRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private AssignmentRepository assignmentRepository;

    @BeforeEach
    void clean() {
        assignmentRepository.deleteAllInBatch();
        submissionRepository.deleteAllInBatch();
        reviewerRepository.deleteAllInBatch();
    }

    @Test
    void concurrentAssignmentsNeverExceedAnyReviewerCapacity() throws Exception {
        // 3 名评审人，每人最多同时评审 2 篇；5 篇投稿各需 1 人 → 总需求 5 大于总容量 6 可满足。
        for (int i = 1; i <= 3; i++) {
            reviewerRepository.saveAndFlush(
                    new Reviewer("R" + i, "Univ" + i, Set.of("ML", "NLP"), 2, true));
        }
        for (int i = 1; i <= 5; i++) {
            submissionRepository.saveAndFlush(new Submission(
                    "MS-" + i, Set.of("ML", "NLP"),
                    Set.of(new Author("A" + i, "AuthorOrg" + i)), 1));
        }

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            String manuscriptNo = "MS-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                assignmentService.autoAssign(manuscriptNo);
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        // 5 篇均成功，且每名评审人的有效任务数不超过 2。
        assertThat(assignmentRepository.count()).isEqualTo(5);
        for (Reviewer reviewer : reviewerRepository.findAll()) {
            long active = assignmentRepository.countActiveByReviewerId(reviewer.getId());
            assertThat(active)
                    .as("评审人 %s 的有效任务数 %d 超过上限 %d",
                            reviewer.getCode(), active, reviewer.getMaxAssignments())
                    .isLessThanOrEqualTo(reviewer.getMaxAssignments());
        }
    }

    @Test
    void concurrentContentionFailsWholeTransactionWhenCapacityExhausted() throws Exception {
        // 2 名评审人各容量 1；2 篇投稿各需 2 人 → 每个事务都要同时拿到两人，
        // 串行化后必有一篇因锁内容量不足而整体失败。
        reviewerRepository.saveAndFlush(new Reviewer("R1", "Univ1", Set.of("ML"), 1, true));
        reviewerRepository.saveAndFlush(new Reviewer("R2", "Univ2", Set.of("ML"), 1, true));
        for (int i = 1; i <= 2; i++) {
            submissionRepository.saveAndFlush(new Submission(
                    "BIG-MS-" + i, Set.of("ML"),
                    Set.of(new Author("A" + i, "AuthorOrg" + i)), 2));
        }

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            String manuscriptNo = "BIG-MS-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    assignmentService.autoAssign(manuscriptNo);
                } catch (BusinessRuleException expected) {
                    // 容量竞争下整笔失败、回滚，属预期行为。
                }
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        // 只有一篇拿到两人；失败的一篇无任何分配记录。
        assertThat(assignmentRepository.count()).isEqualTo(2);
        for (Reviewer reviewer : reviewerRepository.findAll()) {
            assertThat(assignmentRepository.countActiveByReviewerId(reviewer.getId())).isLessThanOrEqualTo(1);
        }
    }
}
