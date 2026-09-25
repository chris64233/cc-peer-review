package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import com.chris64233.cc.peerreview.domain.Author;
import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.domain.Submission;
import com.chris64233.cc.peerreview.domain.SubmissionStatus;
import com.chris64233.cc.peerreview.exception.BusinessRuleException;
import com.chris64233.cc.peerreview.exception.ConflictException;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import com.chris64233.cc.peerreview.web.dto.SubmissionAssignmentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:peerreview-service-test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
    void clean() {
        assignmentRepository.deleteAllInBatch();
        submissionRepository.deleteAllInBatch();
        reviewerRepository.deleteAllInBatch();
    }

    @Test
    void autoAssignPicksByTopicMatchThenLoadThenId() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 2);
        saveReviewer("R2", "Stanford", Set.of("ML", "NLP"), 5, 1);
        saveReviewer("R3", "CMU", Set.of("NLP"), 5, 0);
        saveSubmission("MS001", Set.of("ML", "NLP"), Set.of(new Author("A1", "Berkeley")), 3);

        SubmissionAssignmentDto detail = assignmentService.autoAssign("MS001");

        assertThat(detail.submissionStatus()).isEqualTo(SubmissionStatus.ASSIGNED.name());
        assertThat(detail.activeReviewers()).isEqualTo(3);
        // R2 匹配 2 个主题排第一；R1、R3 各匹配 1 个且负载不同，负载少者靠前。
        assertThat(detail.assignments().stream().map(assignment -> assignment.reviewerCode()).toList())
                .containsExactly("R2", "R3", "R1");
        detail.assignments().forEach(a ->
                assertThat(a.status()).isEqualTo(AssignmentStatus.ACTIVE.name()));
    }

    @Test
    void autoAssignExcludesDisabledSamePersonSameAffiliationAndFullReviewers() {
        saveReviewer("A1", "OtherU", Set.of("ML"), 5, 0);          // 与作者同一人
        saveReviewer("R2", "Berkeley", Set.of("ML"), 5, 0);        // 与作者同一机构
        saveReviewer("R3", "CMU", Set.of("ML"), 5, 0, false);      // 已停用
        saveReviewer("R4", "Stanford", Set.of("ML"), 1, 1);        // 已满负载
        saveReviewer("R5", "Stanford", Set.of("NLP"), 5, 0);       // 唯一合格
        saveSubmission("MS002", Set.of("ML", "NLP"), Set.of(new Author("A1", "Berkeley")), 2);

        assertThatThrownBy(() -> assignmentService.autoAssign("MS002"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("符合条件的评审人不足");

        // 整笔失败：MS002 上无任何分配记录（其他稿件上预置的负载不受影响），任何人的工作量都不增加。
        assertThat(assignmentRepository.findBySubmissionManuscriptNo("MS002")).isEmpty();
        assertThat(submissionRepository.findByManuscriptNo("MS002").orElseThrow().getStatus())
                .isEqualTo(SubmissionStatus.PENDING);
        assertThat(assignmentRepository.countActiveByReviewerId(
                reviewerRepository.findByCode("R5").orElseThrow().getId())).isZero();
    }

    @Test
    void autoAssignDoesNotDuplicateReviewerOnSameSubmission() {
        saveReviewer("R1", "CMU", Set.of("ML", "NLP"), 5, 0);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5, 0);
        saveSubmission("MS003", Set.of("ML", "NLP"), Set.of(new Author("A1", "Berkeley")), 2);

        assignmentService.autoAssign("MS003");

        // 同一投稿重复触发自动分配将被拒绝（数据库 (submission, reviewer) 唯一约束同时兜底）。
        assertThatThrownBy(() -> assignmentService.autoAssign("MS003"))
                .isInstanceOf(ConflictException.class);
        assertThat(assignmentRepository.count()).isEqualTo(2);
    }

    @Test
    void declineReplacesWithNeverAssignedCandidateAndBecomesShortWhenNoneLeft() {
        saveReviewer("R1", "MIT", Set.of("ML", "NLP"), 5, 0);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5, 0);
        saveReviewer("R3", "CMU", Set.of("NLP"), 5, 0);
        saveSubmission("MS004", Set.of("ML", "NLP"), Set.of(new Author("A1", "Berkeley")), 2);

        // 首次分配：R1（匹配2）、R2（匹配1，ID 小）。
        assignmentService.autoAssign("MS004");

        // R1 拒绝：唯一替补 R3 从未分配过，补入后仍满员。
        SubmissionAssignmentDto afterDecline = assignmentService.decline("MS004", "R1");
        assertThat(afterDecline.submissionStatus()).isEqualTo(SubmissionStatus.ASSIGNED.name());
        assertThat(afterDecline.activeReviewers()).isEqualTo(2);
        assertThat(statusOf(afterDecline, "R1")).isEqualTo(AssignmentStatus.DECLINED.name());
        assertThat(statusOf(afterDecline, "R3")).isEqualTo(AssignmentStatus.ACTIVE.name());

        // R3 拒绝：R1 已分配过（含拒绝记录）不能再次入选，R2 已是该稿件评审人，故无替补 → 缺员。
        SubmissionAssignmentDto shortDetail = assignmentService.decline("MS004", "R3");
        assertThat(shortDetail.submissionStatus()).isEqualTo(SubmissionStatus.SHORT.name());
        assertThat(shortDetail.activeReviewers()).isEqualTo(1);
        assertThat(shortDetail.assignments()).extracting("reviewerCode").containsExactlyInAnyOrder("R1", "R2", "R3");
    }

    @Test
    void repeatedDeclineIsIdempotent() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 0);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5, 0);
        saveReviewer("R3", "CMU", Set.of("ML"), 5, 0);
        saveSubmission("MS005", Set.of("ML"), Set.of(new Author("A1", "Berkeley")), 2);
        assignmentService.autoAssign("MS005");

        SubmissionAssignmentDto first = assignmentService.decline("MS005", "R1");
        long recordsAfterFirst = assignmentRepository.count();
        SubmissionAssignmentDto second = assignmentService.decline("MS005", "R1");

        assertThat(second).usingRecursiveComparison().isEqualTo(first);
        assertThat(assignmentRepository.count()).isEqualTo(recordsAfterFirst);
        assertThat(second.activeReviewers()).isEqualTo(2);
        assertThat(second.submissionStatus()).isEqualTo(SubmissionStatus.ASSIGNED.name());
    }

    @Test
    void declineOfUnknownAssignmentIsRejectedAtomically() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 0);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5, 0);
        saveSubmission("MS006", Set.of("ML"), Set.of(new Author("A1", "Berkeley")), 1);
        assignmentService.autoAssign("MS006");

        // R2 从未分配给该稿件，拒绝操作整体失败，不产生任何记录。
        assertThatThrownBy(() -> assignmentService.decline("MS006", "R2"))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(assignmentRepository.count()).isEqualTo(1);
    }

    @Test
    void topicMatchCountOutranksLoadAndId() {
        saveReviewer("LOW_BUSY", "MIT", Set.of("ML"), 5, 4);
        saveReviewer("HIGH_FREE", "Stanford", Set.of("ML", "NLP", "DB"), 5, 0);
        saveSubmission("MS007", Set.of("ML", "NLP", "DB"), Set.of(new Author("A1", "Berkeley")), 1);

        SubmissionAssignmentDto detail = assignmentService.autoAssign("MS007");

        // 匹配主题数优先：匹配 3 个的 HIGH_FREE 胜过只匹配 1 个的 LOW_BUSY。
        assertThat(detail.assignments()).hasSize(1);
        assertThat(detail.assignments().get(0).reviewerCode()).isEqualTo("HIGH_FREE");
    }

    private String statusOf(SubmissionAssignmentDto detail, String reviewerCode) {
        return detail.assignments().stream()
                .filter(a -> a.reviewerCode().equals(reviewerCode))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private void saveReviewer(String code, String affiliation, Set<String> topics, int max, int activeLoad) {
        Reviewer reviewer = reviewerRepository.save(
                new Reviewer(code, affiliation, topics, max, true));
        // 预置其他稿件上的有效任务，构造当前负载。
        for (int i = 0; i < activeLoad; i++) {
            Submission other = submissionRepository.save(new Submission(
                    "OTHER-" + code + "-" + i, Set.copyOf(topics),
                    Set.of(new Author("X", "OrgX")), 1));
            assignmentRepository.save(new com.chris64233.cc.peerreview.domain.Assignment(
                    other, reviewer, AssignmentStatus.ACTIVE, java.time.Instant.now()));
        }
    }

    private void saveReviewer(String code, String affiliation, Set<String> topics, int max,
                              int activeLoad, boolean enabled) {
        Reviewer reviewer = reviewerRepository.save(
                new Reviewer(code, affiliation, topics, max, enabled));
        for (int i = 0; i < activeLoad; i++) {
            Submission other = submissionRepository.save(new Submission(
                    "OTHER-" + code + "-" + i, Set.copyOf(topics),
                    Set.of(new Author("X", "OrgX")), 1));
            assignmentRepository.save(new com.chris64233.cc.peerreview.domain.Assignment(
                    other, reviewer, AssignmentStatus.ACTIVE, java.time.Instant.now()));
        }
    }

    private void saveSubmission(String manuscriptNo, Set<String> topics, Set<Author> authors, int required) {
        submissionRepository.save(new Submission(manuscriptNo, topics, authors, required));
    }
}
