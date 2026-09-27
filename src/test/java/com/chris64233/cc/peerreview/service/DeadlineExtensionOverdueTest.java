package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentEventType;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import com.chris64233.cc.peerreview.domain.Author;
import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.domain.Submission;
import com.chris64233.cc.peerreview.domain.SubmissionStatus;
import com.chris64233.cc.peerreview.exception.BusinessRuleException;
import com.chris64233.cc.peerreview.exception.ConflictException;
import com.chris64233.cc.peerreview.repository.AssignmentEventRepository;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import com.chris64233.cc.peerreview.web.dto.AssignmentDto;
import com.chris64233.cc.peerreview.web.dto.ExtensionApprovalRequest;
import com.chris64233.cc.peerreview.web.dto.ExtensionRejectionRequest;
import com.chris64233.cc.peerreview.web.dto.ExtensionRequestBody;
import com.chris64233.cc.peerreview.web.dto.OverdueScanResultDto;
import com.chris64233.cc.peerreview.web.dto.SubmissionAssignmentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:peerreview-deadline-test;DB_CLOSE_DELAY=-1",
        "review.deadline.first-round-days=21",
        "review.deadline.round-step-days=7",
        "review.deadline.min-days=7",
        "review.deadline.extension-max-days=14",
        "review.deadline.scan-enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeadlineExtensionOverdueTest {

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
    void deadlineComputedFromRoundAndReviewerRuleWithBasisPersisted() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 10);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5, 0);
        saveSubmission("MS-D1", Set.of("ML"), 1, 2);
        saveSubmission("MS-D2", Set.of("ML"), 2, 1);

        Instant before = Instant.now();
        SubmissionAssignmentDto round1 = assignmentService.autoAssign("MS-D1");
        SubmissionAssignmentDto round2 = assignmentService.autoAssign("MS-D2");
        Instant after = Instant.now();

        // 第 1 轮：R1 个人规则 10 天 < 轮次 21 天 → 10 天；R2 无个人规则 → 21 天。
        AssignmentDto r1 = findByReviewer(round1, "R1");
        AssignmentDto r2 = findByReviewer(round1, "R2");
        assertThat(r1.dueAt()).isBetween(before.plus(Duration.ofDays(10)), after.plus(Duration.ofDays(10)));
        assertThat(r1.deadlineBasis()).contains("round=1", "roundDays=21", "reviewerDays=10", "effectiveDays=10");
        assertThat(r2.dueAt()).isBetween(before.plus(Duration.ofDays(21)), after.plus(Duration.ofDays(21)));
        assertThat(r2.deadlineBasis()).contains("round=1", "roundDays=21", "effectiveDays=21");

        // 第 2 轮：轮次天数收紧为 21-7=14 天；R1 因 ID 靠前入选，个人规则 10 天更紧，生效 10 天。
        AssignmentDto round2Assignment = round2.assignments().get(0);
        assertThat(round2Assignment.reviewerCode()).isEqualTo("R1");
        assertThat(round2Assignment.dueAt())
                .isBetween(before.plus(Duration.ofDays(10)), after.plus(Duration.ofDays(10)));
        assertThat(round2Assignment.deadlineBasis())
                .contains("round=2", "roundDays=14", "effectiveDays=10");
    }

    @Test
    void extensionApproveExtendsDeadlineOnceWithinCapAndLeavesImmutableEvents() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 0);
        saveSubmission("MS-E1", Set.of("ML"), 1, 1);
        assignmentService.autoAssign("MS-E1");
        Instant originalDue = findByReviewer(assignmentService.getDetail("MS-E1"), "R1").dueAt();

        // 申请超过上限（14 天）直接被拒。
        assertThatThrownBy(() -> assignmentService.requestExtension("MS-E1", "R1",
                new ExtensionRequestBody(15, "实验数据未齐", "R1")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("超过上限");

        // 正常申请 → 批准 5 天：截止时间顺延，计算依据追加，事件留痕。
        assignmentService.requestExtension("MS-E1", "R1", new ExtensionRequestBody(5, "实验数据未齐", "R1"));
        SubmissionAssignmentDto approved = assignmentService.approveExtension("MS-E1", "R1",
                new ExtensionApprovalRequest(5, "editor-1"));
        AssignmentDto after = findByReviewer(approved, "R1");
        assertThat(after.dueAt()).isEqualTo(originalDue.plus(Duration.ofDays(5)));
        assertThat(after.deadlineBasis()).contains("extension=+5d by editor-1");
        assertThat(eventTypes(approved)).containsSequence(
                AssignmentEventType.EXTENSION_REQUESTED.name(),
                AssignmentEventType.EXTENSION_APPROVED.name());

        // 每个任务至多批准一次：再次申请/批准均失败。
        assertThatThrownBy(() -> assignmentService.requestExtension("MS-E1", "R1",
                new ExtensionRequestBody(3, null, "R1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("至多批准一次");
        assertThatThrownBy(() -> assignmentService.approveExtension("MS-E1", "R1",
                new ExtensionApprovalRequest(3, "editor-1")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void extensionRejectResolvesRequestAndAllowsNewOne() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 0);
        saveSubmission("MS-E2", Set.of("ML"), 1, 1);
        assignmentService.autoAssign("MS-E2");

        // 没有待审批申请时不能批准/拒绝。
        assertThatThrownBy(() -> assignmentService.approveExtension("MS-E2", "R1",
                new ExtensionApprovalRequest(3, "editor-1")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("没有待审批");
        assertThatThrownBy(() -> assignmentService.rejectExtension("MS-E2", "R1",
                new ExtensionRejectionRequest("editor-1", null)))
                .isInstanceOf(BusinessRuleException.class);

        // 申请 → 重复申请被拒 → 拒绝后可以重新申请。
        assignmentService.requestExtension("MS-E2", "R1", new ExtensionRequestBody(7, null, "R1"));
        assertThatThrownBy(() -> assignmentService.requestExtension("MS-E2", "R1",
                new ExtensionRequestBody(7, null, "R1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("待审批");
        SubmissionAssignmentDto rejected = assignmentService.rejectExtension("MS-E2", "R1",
                new ExtensionRejectionRequest("editor-1", "理由不充分"));
        assertThat(eventTypes(rejected)).containsSequence(
                AssignmentEventType.EXTENSION_REQUESTED.name(),
                AssignmentEventType.EXTENSION_REJECTED.name());

        SubmissionAssignmentDto reRequested = assignmentService.requestExtension("MS-E2", "R1",
                new ExtensionRequestBody(3, "补充理由", "R1"));
        assertThat(eventTypes(reRequested))
                .contains(AssignmentEventType.EXTENSION_REJECTED.name())
                .contains(AssignmentEventType.EXTENSION_REQUESTED.name());
    }

    @Test
    void overdueScanMarksOverdueReleasesLoadAndReassignsAtomically() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 0);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5, 0);
        saveSubmission("MS-O1", Set.of("ML"), 1, 1);
        assignmentService.autoAssign("MS-O1");
        forceOverdue("MS-O1", "R1");

        OverdueScanResultDto result = overdueScanService.scanOnce();
        assertThat(result.processed()).isEqualTo(1);

        SubmissionAssignmentDto detail = assignmentService.getDetail("MS-O1");
        // 原任务逾期并记录原因；负载释放；替补 R2 原子补入并带新截止期。
        AssignmentDto oldTask = findByReviewer(detail, "R1");
        assertThat(oldTask.status()).isEqualTo(AssignmentStatus.OVERDUE.name());
        assertThat(oldTask.overdueReason()).contains("未提交评审意见");
        assertThat(activeLoad("R1")).isZero();
        AssignmentDto replacement = findByReviewer(detail, "R2");
        assertThat(replacement.status()).isEqualTo(AssignmentStatus.ACTIVE.name());
        assertThat(replacement.dueAt()).isAfter(Instant.now());
        assertThat(replacement.deadlineBasis()).contains("round=1");
        assertThat(detail.submissionStatus()).isEqualTo(SubmissionStatus.ASSIGNED.name());
        // 改派历史完整留痕。
        assertThat(eventTypes(detail)).contains(
                AssignmentEventType.OVERDUE.name(),
                AssignmentEventType.REASSIGNED.name(),
                AssignmentEventType.ASSIGNED.name());
    }

    @Test
    void lateSubmissionAfterOverdueDoesNotRestoreTask() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 0);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5, 0);
        saveSubmission("MS-O2", Set.of("ML"), 1, 1);
        assignmentService.autoAssign("MS-O2");
        forceOverdue("MS-O2", "R1");
        overdueScanService.scanOnce();

        // 原评审人迟到提交：被拒绝，任务不恢复，负载不回升，事件独立留痕。
        assertThatThrownBy(() -> assignmentService.submitReview("MS-O2", "R1", "迟到的意见"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("迟到意见不能恢复任务");

        SubmissionAssignmentDto detail = assignmentService.getDetail("MS-O2");
        assertThat(findByReviewer(detail, "R1").status()).isEqualTo(AssignmentStatus.OVERDUE.name());
        assertThat(findByReviewer(detail, "R1").submittedAt()).isNull();
        assertThat(activeLoad("R1")).isZero();
        assertThat(eventTypes(detail)).contains(AssignmentEventType.LATE_SUBMITTED.name());
    }

    @Test
    void submittedTaskIsTerminalAndNeverReassignedByScan() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 0);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5, 0);
        saveSubmission("MS-O3", Set.of("ML"), 1, 1);
        assignmentService.autoAssign("MS-O3");

        SubmissionAssignmentDto submitted = assignmentService.submitReview("MS-O3", "R1", "同意接收");
        assertThat(findByReviewer(submitted, "R1").status()).isEqualTo(AssignmentStatus.SUBMITTED.name());
        assertThat(findByReviewer(submitted, "R1").submittedAt()).isNotNull();

        // 已提交任务即使截止期已过，扫描也不得触碰、不得改派。
        forceOverdue("MS-O3", "R1");
        OverdueScanResultDto result = overdueScanService.scanOnce();
        assertThat(result.processed()).isZero();

        SubmissionAssignmentDto detail = assignmentService.getDetail("MS-O3");
        assertThat(detail.assignments()).hasSize(1);
        assertThat(findByReviewer(detail, "R1").status()).isEqualTo(AssignmentStatus.SUBMITTED.name());
        // 重复提交幂等。
        SubmissionAssignmentDto again = assignmentService.submitReview("MS-O3", "R1", "重复提交");
        assertThat(again.assignments()).hasSize(1);
        assertThat(findByReviewer(again, "R1").status()).isEqualTo(AssignmentStatus.SUBMITTED.name());
    }

    @Test
    void scanIsIdempotentAcrossRuns() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 0);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5, 0);
        saveSubmission("MS-O4", Set.of("ML"), 1, 1);
        assignmentService.autoAssign("MS-O4");
        forceOverdue("MS-O4", "R1");

        overdueScanService.scanOnce();
        long assignmentsAfterFirst = assignmentRepository.count();
        long eventsAfterFirst = eventRepository.count();

        // 重复扫描（等价于重启后继续处理）：无新增任务、无新增事件。
        OverdueScanResultDto second = overdueScanService.scanOnce();
        assertThat(second.processed()).isZero();
        assertThat(assignmentRepository.count()).isEqualTo(assignmentsAfterFirst);
        assertThat(eventRepository.count()).isEqualTo(eventsAfterFirst);
    }

    @Test
    void overdueWithoutReplacementBecomesShortAndReleasesLoad() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 0);
        saveSubmission("MS-O5", Set.of("ML"), 1, 1);
        assignmentService.autoAssign("MS-O5");
        forceOverdue("MS-O5", "R1");

        overdueScanService.scanOnce();

        SubmissionAssignmentDto detail = assignmentService.getDetail("MS-O5");
        assertThat(detail.submissionStatus()).isEqualTo(SubmissionStatus.SHORT.name());
        assertThat(findByReviewer(detail, "R1").status()).isEqualTo(AssignmentStatus.OVERDUE.name());
        assertThat(activeLoad("R1")).isZero();
        assertThat(detail.assignments()).hasSize(1);
    }

    @Test
    void detailExposesCurrentReviewersReassignmentsDeadlineChangesAndOverdueReason() {
        saveReviewer("R1", "MIT", Set.of("ML"), 5, 0);
        saveReviewer("R2", "Stanford", Set.of("ML"), 5, 0);
        saveSubmission("MS-Q1", Set.of("ML"), 1, 1);
        assignmentService.autoAssign("MS-Q1");

        // 延期改变截止期。
        assignmentService.requestExtension("MS-Q1", "R1", new ExtensionRequestBody(4, null, "R1"));
        assignmentService.approveExtension("MS-Q1", "R1", new ExtensionApprovalRequest(4, "editor-9"));
        // 随后逾期并改派。
        forceOverdue("MS-Q1", "R1");
        overdueScanService.scanOnce();

        SubmissionAssignmentDto detail = assignmentService.getDetail("MS-Q1");
        // 当前有效评审人为替补 R2。
        assertThat(detail.assignments().stream()
                .filter(a -> a.status().equals(AssignmentStatus.ACTIVE.name()))
                .map(AssignmentDto::reviewerCode).toList())
                .containsExactly("R2");
        // 逾期原因、截止期变化与历次改派均可查。
        assertThat(findByReviewer(detail, "R1").overdueReason()).isNotBlank();
        assertThat(findByReviewer(detail, "R1").deadlineBasis()).contains("extension=+4d");
        assertThat(detail.events().stream().map(e -> e.type()).toList()).containsExactly(
                AssignmentEventType.ASSIGNED.name(),
                AssignmentEventType.EXTENSION_REQUESTED.name(),
                AssignmentEventType.EXTENSION_APPROVED.name(),
                AssignmentEventType.OVERDUE.name(),
                AssignmentEventType.ASSIGNED.name(),
                AssignmentEventType.REASSIGNED.name());
    }

    private AssignmentDto findByReviewer(SubmissionAssignmentDto detail, String reviewerCode) {
        return detail.assignments().stream()
                .filter(a -> a.reviewerCode().equals(reviewerCode))
                .findFirst()
                .orElseThrow();
    }

    private java.util.List<String> eventTypes(SubmissionAssignmentDto detail) {
        return detail.events().stream().map(e -> e.type()).toList();
    }

    private long activeLoad(String reviewerCode) {
        return assignmentRepository.countActiveByReviewerId(
                reviewerRepository.findByCode(reviewerCode).orElseThrow().getId());
    }

    /** 将该评审人在此稿件上的任务截止期改到过去，模拟时间流逝。 */
    private void forceOverdue(String manuscriptNo, String reviewerCode) {
        Assignment assignment = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode(manuscriptNo, reviewerCode).get(0);
        assignment.setDueAt(Instant.now().minusSeconds(3600));
        assignmentRepository.saveAndFlush(assignment);
    }

    private void saveReviewer(String code, String affiliation, Set<String> topics, int max, int reviewDays) {
        Reviewer reviewer = reviewerRepository.save(new Reviewer(code, affiliation, topics, max, true));
        reviewer.setReviewDays(reviewDays);
        reviewerRepository.save(reviewer);
    }

    private void saveSubmission(String manuscriptNo, Set<String> topics, int round, int required) {
        Submission submission = new Submission(
                manuscriptNo, topics, Set.of(new Author("A1", "Berkeley")), required);
        submission.setRound(round);
        submissionRepository.save(submission);
    }
}
