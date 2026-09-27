package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import com.chris64233.cc.peerreview.repository.AssignmentEventRepository;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:peerreview-deadline-api-test;DB_CLOSE_DELAY=-1",
        "review.deadline.scan-enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeadlineApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AssignmentRepository assignmentRepository;
    @Autowired
    private AssignmentEventRepository eventRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private ReviewerRepository reviewerRepository;

    @BeforeEach
    void clean() {
        eventRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        submissionRepository.deleteAllInBatch();
        reviewerRepository.deleteAllInBatch();
    }

    @Test
    void deadlineExtensionOverdueLifecycleThroughApi() throws Exception {
        createReviewer("R1", "MIT", List.of("ML"), 5, 10);
        createReviewer("R2", "Stanford", List.of("ML"), 5, 0);
        createReviewer("R3", "CMU", List.of("ML"), 5, 0);
        createSubmission("MS-DL-1", 2, 2);

        // 自动分配：带截止期与计算依据；第 2 轮轮次天数 14，R1 个人规则 10 天更紧。
        mockMvc.perform(post("/api/submissions/MS-DL-1/assignments/auto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments[0].reviewerCode").value("R1"))
                .andExpect(jsonPath("$.assignments[0].dueAt").exists())
                .andExpect(jsonPath("$.assignments[0].deadlineBasis").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("round=2"),
                                org.hamcrest.Matchers.containsString("effectiveDays=10"))))
                .andExpect(jsonPath("$.assignments[1].deadlineBasis").value(
                        org.hamcrest.Matchers.containsString("effectiveDays=14")))
                .andExpect(jsonPath("$.events[0].type").value("ASSIGNED"));

        // 延期申请 → 批准：截止期顺延，事件留痕。
        mockMvc.perform(post("/api/submissions/MS-DL-1/assignments/R1/extensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestedDays\":5,\"reason\":\"数据未齐\",\"requestedBy\":\"R1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[?(@.type=='EXTENSION_REQUESTED')]").exists());
        mockMvc.perform(post("/api/submissions/MS-DL-1/assignments/R1/extensions/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedDays\":5,\"decidedBy\":\"editor-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[?(@.type=='EXTENSION_APPROVED')]").exists())
                .andExpect(jsonPath("$.assignments[0].deadlineBasis").value(
                        org.hamcrest.Matchers.containsString("extension=+5d")));

        // 超过上限的批准被 422 拒绝；每个任务只能批准一次。
        mockMvc.perform(post("/api/submissions/MS-DL-1/assignments/R2/extensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestedDays\":99,\"requestedBy\":\"R2\"}"))
                .andExpect(status().isUnprocessableEntity());

        // R1 按时提交评审意见（ACTIVE 任务排在前面，R1 已提交后须按评审人过滤断言）。
        mockMvc.perform(post("/api/submissions/MS-DL-1/assignments/R1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"同意接收\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments[?(@.reviewerCode=='R1')].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.assignments[?(@.reviewerCode=='R1')].submittedAt").exists());

        // R2 逾期未提交：手动触发扫描 → 逾期、释放负载、R3 替补。
        forceOverdue("MS-DL-1", "R2");
        mockMvc.perform(post("/api/admin/overdue-scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(1));

        mockMvc.perform(get("/api/submissions/MS-DL-1/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments[?(@.reviewerCode=='R2')].status").value("OVERDUE"))
                .andExpect(jsonPath("$.assignments[?(@.reviewerCode=='R2')].overdueReason").exists())
                .andExpect(jsonPath("$.assignments[?(@.reviewerCode=='R3')].status").value("ACTIVE"))
                .andExpect(jsonPath("$.events[?(@.type=='OVERDUE')]").exists())
                .andExpect(jsonPath("$.events[?(@.type=='REASSIGNED')]").exists());

        // 重复扫描幂等。
        mockMvc.perform(post("/api/admin/overdue-scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(0));

        // R2 迟到提交：409，任务不恢复，迟到事件留痕。
        mockMvc.perform(post("/api/submissions/MS-DL-1/assignments/R2/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"迟到的意见\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/submissions/MS-DL-1/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments[?(@.reviewerCode=='R2')].status").value("OVERDUE"))
                .andExpect(jsonPath("$.events[?(@.type=='LATE_SUBMITTED')]").exists());
    }

    private void createReviewer(String code, String affiliation, List<String> topics,
                                int maxAssignments, int reviewDays) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "code", code,
                "affiliation", affiliation,
                "topics", topics,
                "maxAssignments", maxAssignments,
                "reviewDays", reviewDays));
        mockMvc.perform(post("/api/reviewers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private void createSubmission(String manuscriptNo, int round, int requiredReviewers) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "manuscriptNo", manuscriptNo,
                "topics", List.of("ML"),
                "authors", List.of(Map.of("code", "A1", "affiliation", "Berkeley")),
                "requiredReviewers", requiredReviewers,
                "round", round));
        mockMvc.perform(post("/api/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.round").value(round));
    }

    private void forceOverdue(String manuscriptNo, String reviewerCode) {
        Assignment assignment = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode(manuscriptNo, reviewerCode).stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .findFirst().orElseThrow();
        assignment.setDueAt(Instant.now().minusSeconds(3600));
        assignmentRepository.saveAndFlush(assignment);
    }
}
