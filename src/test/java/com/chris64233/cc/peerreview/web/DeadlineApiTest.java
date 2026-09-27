package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ExtensionEventRepository;
import com.chris64233.cc.peerreview.repository.ReviewRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:peerreview-deadline-api-test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeadlineApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AssignmentRepository assignmentRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private ReviewerRepository reviewerRepository;
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
    void deadlineExtensionOverdueAndLateReviewThroughApi() throws Exception {
        createReviewer("R1", "MIT", List.of("ML", "NLP"), 5, 5);
        createReviewer("R2", "Stanford", List.of("ML"), 5, null);

        // 第 2 轮稿件：基础 14 天 > R1 的 5 天 → 截止期为 14 天后。
        String submission = objectMapper.writeValueAsString(Map.of(
                "manuscriptNo", "MS-DL-1",
                "topics", List.of("ML"),
                "authors", List.of(Map.of("code", "A1", "affiliation", "Berkeley")),
                "requiredReviewers", 1,
                "reviewRound", 2));
        mockMvc.perform(post("/api/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewRound").value(2));

        mockMvc.perform(post("/api/submissions/MS-DL-1/assignments/auto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments[0].reviewerCode").value("R1"))
                .andExpect(jsonPath("$.assignments[0].reviewRound").value(2))
                .andExpect(jsonPath("$.assignments[0].dueAt").exists())
                .andExpect(jsonPath("$.assignments[0].deadlineBasis")
                        .value("round=2;roundDays=14;reviewerDays=5;extensionDays=0"));

        // 申请 → 批准：截止期顺延，事件时间线完整。
        mockMvc.perform(post("/api/submissions/MS-DL-1/assignments/R1/extension/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":7,\"reason\":\"需要更多时间\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("REQUESTED"))
                .andExpect(jsonPath("$[0].days").value(7));
        mockMvc.perform(post("/api/submissions/MS-DL-1/assignments/R1/extension/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"editor\":\"ED1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].type").value("APPROVED"))
                .andExpect(jsonPath("$[1].actor").value("ED1"));

        mockMvc.perform(get("/api/submissions/MS-DL-1/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments[0].extensionDays").value(7))
                .andExpect(jsonPath("$.assignments[0].deadlineBasis")
                        .value("round=2;roundDays=14;reviewerDays=5;extensionDays=7"))
                .andExpect(jsonPath("$.assignments[0].extensionEvents.length()").value(2));

        // 将截止期拨到过去并触发扫描：R1 逾期释放负载，R2 原子替补。
        backdateDueAt("MS-DL-1", "R1");
        mockMvc.perform(post("/api/overdue-scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdueMarked").value(1))
                .andExpect(jsonPath("$.reassigned").value(1));

        mockMvc.perform(get("/api/submissions/MS-DL-1/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeReviewers").value(1))
                .andExpect(jsonPath("$.reassignments.length()").value(1))
                .andExpect(jsonPath("$.reassignments[0].fromReviewerCode").value("R1"))
                .andExpect(jsonPath("$.reassignments[0].toReviewerCode").value("R2"))
                .andExpect(jsonPath("$.assignments[?(@.reviewerCode=='R1')].status").value("OVERDUE"))
                .andExpect(jsonPath("$.assignments[?(@.reviewerCode=='R1')].statusReason",
                        org.hamcrest.Matchers.hasItem(containsString("逾期"))));

        // 重复扫描幂等。
        mockMvc.perform(post("/api/overdue-scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueFound").value(0));

        // R1 迟交意见：仅存档 late=true，任务不恢复。
        mockMvc.perform(post("/api/submissions/MS-DL-1/assignments/R1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"迟到的意见\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true));
        mockMvc.perform(get("/api/submissions/MS-DL-1/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments[?(@.reviewerCode=='R1')].status").value("OVERDUE"))
                .andExpect(jsonPath("$.activeReviewers").value(1));

        // R2 按期提交：任务完成。
        mockMvc.perform(post("/api/submissions/MS-DL-1/assignments/R2/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"替补按期提交\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(false));
    }

    @Test
    void extensionValidationErrorsReturn422() throws Exception {
        createReviewer("R1", "MIT", List.of("ML"), 5, null);
        String submission = objectMapper.writeValueAsString(Map.of(
                "manuscriptNo", "MS-DL-2",
                "topics", List.of("ML"),
                "authors", List.of(Map.of("code", "A1", "affiliation", "Berkeley")),
                "requiredReviewers", 1));
        mockMvc.perform(post("/api/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/submissions/MS-DL-2/assignments/auto"))
                .andExpect(status().isOk());

        // 超过上限的延期申请。
        mockMvc.perform(post("/api/submissions/MS-DL-2/assignments/R1/extension/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":99}"))
                .andExpect(status().isUnprocessableEntity());

        // 无申请直接批准。
        mockMvc.perform(post("/api/submissions/MS-DL-2/assignments/R1/extension/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"editor\":\"ED1\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 缺少必填字段。
        mockMvc.perform(post("/api/submissions/MS-DL-2/assignments/R1/extension/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private void createReviewer(String code, String affiliation, List<String> topics,
                                int maxAssignments, Integer reviewDays) throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "code", code,
                "affiliation", affiliation,
                "topics", topics,
                "maxAssignments", maxAssignments,
                "enabled", true));
        if (reviewDays != null) {
            body.put("reviewDays", reviewDays);
        }
        mockMvc.perform(post("/api/reviewers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private void backdateDueAt(String manuscriptNo, String reviewerCode) {
        Assignment assignment = assignmentRepository
                .findBySubmissionManuscriptNoAndReviewerCode(manuscriptNo, reviewerCode).get(0);
        assignment.setDueAt(Instant.now().minus(1, ChronoUnit.DAYS));
        assignmentRepository.save(assignment);
    }
}
