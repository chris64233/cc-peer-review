package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:peerreview-api-test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AssignmentApiTest {

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

    @BeforeEach
    void clean() {
        assignmentRepository.deleteAllInBatch();
        submissionRepository.deleteAllInBatch();
        reviewerRepository.deleteAllInBatch();
    }

    @Test
    void fullAssignmentLifecycleThroughApi() throws Exception {
        createReviewer("R1", "MIT", List.of("ML", "NLP"), 2, true);
        createReviewer("R2", "Stanford", List.of("ML"), 2, true);
        createReviewer("R3", "CMU", List.of("NLP"), 1, true);

        String submission = objectMapper.writeValueAsString(Map.of(
                "manuscriptNo", "MS-API-1",
                "topics", List.of("ML", "NLP"),
                "authors", List.of(Map.of("code", "A1", "affiliation", "Berkeley")),
                "requiredReviewers", 2));
        mockMvc.perform(post("/api/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 自动分配：R1（匹配2主题）、R3（匹配1、负载0、ID 大）——R2 与 R3 并列时按负载再按 ID。
        mockMvc.perform(post("/api/submissions/MS-API-1/assignments/auto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$.activeReviewers").value(2))
                .andExpect(jsonPath("$.assignments[0].reviewerCode").value("R1"))
                .andExpect(jsonPath("$.assignments[1].reviewerCode").value("R2"));

        // 负载查询：R1、R2 各占用 1 个任务。
        mockMvc.perform(get("/api/reviewers/loads"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='R1')].activeAssignments").value(1))
                .andExpect(jsonPath("$[?(@.code=='R2')].activeAssignments").value(1))
                .andExpect(jsonPath("$[?(@.code=='R3')].activeAssignments").value(0));

        // R1 拒绝 → R3 替补，替补后 R3 容量 1 已满。
        mockMvc.perform(post("/api/submissions/MS-API-1/assignments/R1/decline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$.activeReviewers").value(2));

        mockMvc.perform(get("/api/reviewers/loads"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='R3')].activeAssignments").value(1))
                .andExpect(jsonPath("$[?(@.code=='R3')].remainingCapacity").value(0));

        // 再次拒绝 R1 幂等。
        mockMvc.perform(post("/api/submissions/MS-API-1/assignments/R1/decline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionStatus").value("ASSIGNED"));

        // 停用 R2 后拒绝 R2：替补只剩已分配过的 R1/停用的 R2 自己，进入缺员但保留 R3。
        mockMvc.perform(patch("/api/reviewers/R2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/submissions/MS-API-1/assignments/R2/decline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionStatus").value("SHORT"))
                .andExpect(jsonPath("$.activeReviewers").value(1));

        mockMvc.perform(get("/api/submissions/MS-API-1/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionStatus").value("SHORT"))
                .andExpect(jsonPath("$.assignments.length()").value(3));
    }

    @Test
    void insufficientReviewersReturns422AndNoAssignmentCreated() throws Exception {
        createReviewer("R1", "MIT", List.of("ML"), 5, true);
        String submission = objectMapper.writeValueAsString(Map.of(
                "manuscriptNo", "MS-API-2",
                "topics", List.of("ML"),
                "authors", List.of(Map.of("code", "A1", "affiliation", "Berkeley")),
                "requiredReviewers", 2));
        mockMvc.perform(post("/api/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/submissions/MS-API-2/assignments/auto"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("符合条件的评审人不足")));

        mockMvc.perform(get("/api/submissions/MS-API-2/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionStatus").value("PENDING"))
                .andExpect(jsonPath("$.assignments.length()").value(0));
    }

    @Test
    void invalidRequestReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "code", "R-BAD",
                "affiliation", "MIT",
                "topics", List.of(),
                "maxAssignments", 0));
        mockMvc.perform(post("/api/reviewers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    private void createReviewer(String code, String affiliation, List<String> topics,
                                int maxAssignments, boolean enabled) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "code", code,
                "affiliation", affiliation,
                "topics", topics,
                "maxAssignments", maxAssignments,
                "enabled", enabled));
        mockMvc.perform(post("/api/reviewers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }
}
