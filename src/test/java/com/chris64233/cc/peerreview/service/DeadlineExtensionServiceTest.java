package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import com.chris64233.cc.peerreview.domain.Author;
import com.chris64233.cc.peerreview.domain.ExtensionEventType;
import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.domain.Submission;
import com.chris64233.cc.peerreview.exception.BusinessRuleException;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ExtensionEventRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import com.chris64233.cc.peerreview.web.dto.AssignmentDto;
import com.chris64233.cc.peerreview.web.dto.ExtensionEventDto;
import com.chris64233.cc.peerreview.web.dto.SubmissionAssignmentDto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:peerreview-deadline-test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeadlineExtensionServiceTest {

    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private ExtensionService extensionService;
    @Autowired
    private ReviewerRepository reviewerRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private AssignmentRepository assignmentRepository;
    @Autowired
    private ExtensionEventRepository extensionEventRepository;

    @BeforeEach
    void clean() {
        extensionEventRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        submissionRepository.deleteAllInBatch();
        reviewerRepository.deleteAllInBatch();
    }

    @Test
    void deadlineComputedFromRoundAndReviewerRulesAndBasisPersisted() {
        // R1 周转 5 天（短于轮次基础天数），R2 周转 30 天（长于轮次基础天数）。
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 5);
        saveReviewer("R2", "Stanford", Set.of("DB"), 5, 30);
        saveSubmission("MS-ROUND1", Set.of("ML"), 1);
        saveSubmission("MS-ROUND3", Set.of("DB"), 3); // 未配置的轮次走默认 10 天

        Instant before = Instant.now();
        SubmissionAssignmentDto detail1 = assignmentService.autoAssign("MS-ROUND1");
        SubmissionAssignmentDto detail2 = assignmentService.autoAssign("MS-ROUND3");
        Instant after = Instant.now();

        // 轮次 1 基础 21 天 > 评审人 5 天 → 21 天。
        AssignmentDto a1 = detail1.assignments().get(0);
        assertThat(a1.reviewRound()).isEqualTo(1);
        assertThat(a1.dueAt()).isBetween(
                before.plus(21, ChronoUnit.DAYS), after.plus(21, ChronoUnit.DAYS));
        assertThat(a1.deadlineBasis()).isEqualTo("round=1;roundDays=21;reviewerDays=5;extensionDays=0");
        assertThat(a1.extensionDays()).isZero();

        // 评审人 30 天 > 轮次默认 10 天 → 30 天（评审人规则只能延长不能压缩）。
        AssignmentDto a2 = detail2.assignments().get(0);
        assertThat(a2.reviewRound()).isEqualTo(3);
        assertThat(a2.dueAt()).isBetween(
                before.plus(30, ChronoUnit.DAYS), after.plus(30, ChronoUnit.DAYS));
        assertThat(a2.deadlineBasis()).isEqualTo("round=3;roundDays=10;reviewerDays=30;extensionDays=0");

        // 计算依据快照已落库。
        Assignment persisted = assignmentRepository.findById(a1.assignmentId()).orElseThrow();
        assertThat(persisted.getRoundDays()).isEqualTo(21);
        assertThat(persisted.getReviewerDays()).isEqualTo(5);
    }

    @Test
    void extensionApprovalExtendsDeadlineOnceAndRecordsImmutableEvents() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 14);
        saveSubmission("MS-EXT", Set.of("ML"), 1);
        assignmentService.autoAssign("MS-EXT");
        Instant originalDue = dueAtOf("MS-EXT", "R1");

        List<ExtensionEventDto> requested = extensionService.request("MS-EXT", "R1", 7, "实验复现耗时");
        assertThat(requested).hasSize(1);
        assertThat(requested.get(0).type()).isEqualTo(ExtensionEventType.REQUESTED.name());
        assertThat(requested.get(0).days()).isEqualTo(7);
        assertThat(requested.get(0).actor()).isEqualTo("R1");

        // 待审批期间重复申请幂等：不产生新事件。
        assertThat(extensionService.request("MS-EXT", "R1", 7, "实验复现耗时")).hasSize(1);

        List<ExtensionEventDto> approved = extensionService.approve("MS-EXT", "R1", "ED1", null, "同意");
        assertThat(approved).hasSize(2);
        ExtensionEventDto approval = approved.get(1);
        assertThat(approval.type()).isEqualTo(ExtensionEventType.APPROVED.name());
        assertThat(approval.actor()).isEqualTo("ED1");
        assertThat(approval.previousDueAt()).isEqualTo(originalDue);
        assertThat(approval.resultingDueAt()).isEqualTo(originalDue.plus(7, ChronoUnit.DAYS));

        // 截止期顺延 7 天，计算依据同步更新，事件落库不可变。
        assertThat(dueAtOf("MS-EXT", "R1")).isEqualTo(originalDue.plus(7, ChronoUnit.DAYS));
        Assignment assignment = assignmentOf("MS-EXT", "R1");
        assertThat(assignment.getExtensionDays()).isEqualTo(7);
        assertThat(assignment.getDeadlineBasis()).isEqualTo("round=1;roundDays=21;reviewerDays=14;extensionDays=7");
        assertThat(extensionEventRepository.findByAssignmentIdOrderByIdAsc(assignment.getId())).hasSize(2);

        // 重复批准幂等：不追加事件、不再顺延。
        assertThat(extensionService.approve("MS-EXT", "R1", "ED1", null, null)).hasSize(2);
        assertThat(dueAtOf("MS-EXT", "R1")).isEqualTo(originalDue.plus(7, ChronoUnit.DAYS));

        // 每个任务最多批准一次：批准后再次申请被拒绝。
        assertThatThrownBy(() -> extensionService.request("MS-EXT", "R1", 3, "再次申请"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已批准过一次延期");
    }

    @Test
    void rejectionKeepsDeadlineAndAllowsNewRequest() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 14);
        saveSubmission("MS-REJ", Set.of("ML"), 1);
        assignmentService.autoAssign("MS-REJ");
        Instant originalDue = dueAtOf("MS-REJ", "R1");

        extensionService.request("MS-REJ", "R1", 5, "时间冲突");
        List<ExtensionEventDto> rejected = extensionService.reject("MS-REJ", "R1", "ED1", "期限已属宽松");
        assertThat(rejected).hasSize(2);
        assertThat(rejected.get(1).type()).isEqualTo(ExtensionEventType.REJECTED.name());
        assertThat(dueAtOf("MS-REJ", "R1")).isEqualTo(originalDue);

        // 重复拒绝幂等：不追加事件。
        assertThat(extensionService.reject("MS-REJ", "R1", "ED1", null)).hasSize(2);

        // 被拒绝后可以再次申请并获批。
        extensionService.request("MS-REJ", "R1", 3, "补充说明后再次申请");
        List<ExtensionEventDto> approved = extensionService.approve("MS-REJ", "R1", "ED2", 2, "部分同意");
        assertThat(approved).hasSize(4);
        assertThat(approved.get(3).type()).isEqualTo(ExtensionEventType.APPROVED.name());
        assertThat(approved.get(3).days()).isEqualTo(2);
        assertThat(dueAtOf("MS-REJ", "R1")).isEqualTo(originalDue.plus(2, ChronoUnit.DAYS));
    }

    @Test
    void extensionRequestsAreValidated() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 14);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5, 14);
        saveSubmission("MS-VAL", Set.of("ML"), 1);
        assignmentService.autoAssign("MS-VAL");

        // 超过上限（14 天）的申请被拒绝。
        assertThatThrownBy(() -> extensionService.request("MS-VAL", "R1", 15, "太久"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("超过上限");

        // 没有待审批申请时不能批准或拒绝。
        assertThatThrownBy(() -> extensionService.approve("MS-VAL", "R1", "ED1", null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("没有待审批的延期申请");
        assertThatThrownBy(() -> extensionService.reject("MS-VAL", "R1", "ED1", null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("没有待审批的延期申请");

        // 批准天数同样受上限约束。
        extensionService.request("MS-VAL", "R1", 3, "合理申请");
        assertThatThrownBy(() -> extensionService.approve("MS-VAL", "R1", "ED1", 99, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("批准天数必须在");

        // 已结束（拒绝）的任务不能申请延期。
        assignmentService.decline("MS-VAL", "R1");
        assertThatThrownBy(() -> extensionService.request("MS-VAL", "R1", 3, "已拒绝"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("仅进行中的任务可以申请延期");
    }

    private Instant dueAtOf(String manuscriptNo, String reviewerCode) {
        return assignmentOf(manuscriptNo, reviewerCode).getDueAt();
    }

    private Assignment assignmentOf(String manuscriptNo, String reviewerCode) {
        return assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode(manuscriptNo, reviewerCode)
                .get(0);
    }

    private void saveReviewer(String code, String affiliation, Set<String> topics,
                              int max, int reviewDays) {
        Reviewer reviewer = new Reviewer(code, affiliation, topics, max, true);
        reviewer.setReviewDays(reviewDays);
        reviewerRepository.save(reviewer);
    }

    private void saveSubmission(String manuscriptNo, Set<String> topics, int reviewRound) {
        Submission submission = new Submission(
                manuscriptNo, topics, Set.of(new Author("A1", "Berkeley")), 1);
        submission.setReviewRound(reviewRound);
        submissionRepository.save(submission);
    }
}
