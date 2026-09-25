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
import com.chris64233.cc.peerreview.web.dto.SubmissionAssignmentDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AssignmentServiceTest {

    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private ReviewerRepository reviewerRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private AssignmentRepository assignmentRepository;

    @BeforeEach
    void cleanUp() {
        assignmentRepository.deleteAll();
        submissionRepository.deleteAll();
        reviewerRepository.deleteAll();
    }

    private Reviewer reviewer(String no, String name, String affiliation, Set<String> topics,
                              int maxConcurrent, boolean active) {
        return reviewerRepository.save(new Reviewer(no, name, affiliation, topics, maxConcurrent, active));
    }

    private Submission submission(String manuscriptNo, Set<String> topics, int required) {
        return submissionRepository.save(new Submission(manuscriptNo, topics,
                List.of(new Author("作者甲", "清华大学")), required));
    }

    private List<Long> activeReviewerIds(SubmissionAssignmentDetail detail) {
        return detail.assignments().stream()
                .filter(a -> a.status() == AssignmentStatus.ACTIVE)
                .map(AssignmentView::reviewerId)
                .toList();
    }

    @Test
    void assignSortsByMatchCountThenLoadThenId() {
        Reviewer lowMatch = reviewer("R1", "评审一", "北京大学", Set.of("AI"), 5, true);
        Reviewer best = reviewer("R2", "评审二", "北京大学", Set.of("AI", "ML"), 5, true);
        Reviewer busy = reviewer("R3", "评审三", "北京大学", Set.of("AI", "ML"), 5, true);
        Reviewer tiedWithBest = reviewer("R4", "评审四", "北京大学", Set.of("AI", "ML"), 5, true);

        Submission other = submission("M-OTHER", Set.of("AI"), 1);
        assignmentRepository.save(new Assignment(other, busy));

        Submission submission = submission("M-001", Set.of("AI", "ML"), 3);
        SubmissionAssignmentDetail detail = assignmentService.assign(submission.getId());

        assertThat(detail.status()).isEqualTo(SubmissionStatus.ASSIGNED);
        assertThat(activeReviewerIds(detail))
                .containsExactly(best.getId(), tiedWithBest.getId(), busy.getId());
        assertThat(activeReviewerIds(detail)).doesNotContain(lowMatch.getId());
    }

    @Test
    void assignExcludesIneligibleReviewers() {
        reviewer("R1", "评审一", "北京大学", Set.of("AI"), 5, false);          // 未启用
        reviewer("R2", "作者甲", "复旦大学", Set.of("AI"), 5, true);          // 作者本人
        reviewer("R3", "评审三", "清华大学", Set.of("AI"), 5, true);          // 与作者同机构
        Reviewer overloaded = reviewer("R4", "评审四", "北京大学", Set.of("AI"), 1, true);
        reviewer("R5", "评审五", "北京大学", Set.of("BIO"), 5, true);         // 主题不匹配
        Reviewer eligible = reviewer("R6", "评审六", "北京大学", Set.of("AI"), 5, true);

        Submission other = submission("M-OTHER", Set.of("AI"), 1);
        assignmentRepository.save(new Assignment(other, overloaded));

        Submission submission = submission("M-002", Set.of("AI"), 1);
        SubmissionAssignmentDetail detail = assignmentService.assign(submission.getId());

        assertThat(activeReviewerIds(detail)).containsExactly(eligible.getId());
    }

    @Test
    void assignFailsAtomicallyWhenNotEnoughCandidates() {
        reviewer("R1", "评审一", "北京大学", Set.of("AI"), 5, true);
        reviewer("R2", "评审二", "北京大学", Set.of("AI"), 5, true);
        Submission submission = submission("M-003", Set.of("AI"), 3);

        assertThatThrownBy(() -> assignmentService.assign(submission.getId()))
                .isInstanceOf(AssignmentException.class);

        assertThat(assignmentRepository.findAll()).isEmpty();
        assertThat(submissionRepository.findById(submission.getId()).orElseThrow().getStatus())
                .isEqualTo(SubmissionStatus.PENDING);
        assertThat(assignmentService.getReviewerLoad(
                reviewerRepository.findAll().getFirst().getId()).activeAssignments()).isZero();
    }

    @Test
    void declinePicksBestNeverAssignedReplacement() {
        Reviewer first = reviewer("R1", "评审一", "北京大学", Set.of("AI"), 5, true);
        Reviewer replacement = reviewer("R2", "评审二", "北京大学", Set.of("AI"), 5, true);
        Submission submission = submission("M-004", Set.of("AI"), 1);

        SubmissionAssignmentDetail assigned = assignmentService.assign(submission.getId());
        assertThat(activeReviewerIds(assigned)).containsExactly(first.getId());

        SubmissionAssignmentDetail afterDecline = assignmentService.decline(submission.getId(), first.getId());

        assertThat(afterDecline.status()).isEqualTo(SubmissionStatus.ASSIGNED);
        assertThat(activeReviewerIds(afterDecline)).containsExactly(replacement.getId());
        assertThat(afterDecline.assignments())
                .filteredOn(a -> a.reviewerId().equals(first.getId()))
                .singleElement()
                .extracting(AssignmentView::status)
                .isEqualTo(AssignmentStatus.DECLINED);
    }

    @Test
    void declineWithoutReplacementMarksUnderstaffedButKeepsActiveAssignments() {
        Reviewer staying = reviewer("R1", "评审一", "北京大学", Set.of("AI"), 5, true);
        Reviewer leaving = reviewer("R2", "评审二", "北京大学", Set.of("AI"), 5, true);
        Submission submission = submission("M-005", Set.of("AI"), 2);
        assignmentService.assign(submission.getId());

        SubmissionAssignmentDetail detail = assignmentService.decline(submission.getId(), leaving.getId());

        assertThat(detail.status()).isEqualTo(SubmissionStatus.UNDERSTAFFED);
        assertThat(activeReviewerIds(detail)).containsExactly(staying.getId());
    }

    @Test
    void declineIsIdempotent() {
        Reviewer first = reviewer("R1", "评审一", "北京大学", Set.of("AI"), 5, true);
        reviewer("R2", "评审二", "北京大学", Set.of("AI"), 5, true);
        Submission submission = submission("M-006", Set.of("AI"), 1);
        assignmentService.assign(submission.getId());

        SubmissionAssignmentDetail first1 = assignmentService.decline(submission.getId(), first.getId());
        SubmissionAssignmentDetail second = assignmentService.decline(submission.getId(), first.getId());

        assertThat(second.assignments()).hasSize(first1.assignments().size());
        assertThat(activeReviewerIds(second)).isEqualTo(activeReviewerIds(first1));
        assertThat(assignmentRepository.findAll()).hasSize(2);
    }

    @Test
    void declinedReviewerIsNeverReassignedToSameSubmission() {
        Reviewer only = reviewer("R1", "评审一", "北京大学", Set.of("AI"), 5, true);
        Submission submission = submission("M-007", Set.of("AI"), 1);
        assignmentService.assign(submission.getId());

        SubmissionAssignmentDetail detail = assignmentService.decline(submission.getId(), only.getId());

        assertThat(detail.status()).isEqualTo(SubmissionStatus.UNDERSTAFFED);
        assertThat(detail.assignments()).hasSize(1);
        assertThat(detail.assignments().getFirst().status()).isEqualTo(AssignmentStatus.DECLINED);
    }

    @Test
    void concurrentAssignmentsNeverExceedWorkloadCap() throws Exception {
        Reviewer shared = reviewer("R1", "评审一", "北京大学", Set.of("AI"), 2, true);
        List<Submission> submissions = List.of(
                submission("M-A", Set.of("AI"), 1),
                submission("M-B", Set.of("AI"), 1),
                submission("M-C", Set.of("AI"), 1));

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = submissions.stream()
                .map(s -> pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        assignmentService.assign(s.getId());
                        return true;
                    } catch (AssignmentException e) {
                        return false;
                    }
                }))
                .toList();
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        long successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        pool.shutdown();

        assertThat(successes).isEqualTo(2);
        assertThat(assignmentRepository
                .countByReviewerIdAndStatus(shared.getId(), AssignmentStatus.ACTIVE))
                .isEqualTo(2);
        assertThat(assignmentService.getReviewerLoad(shared.getId()).activeAssignments()).isEqualTo(2);
    }
}
