package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AssignmentRepository assignmentRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private ReviewerRepository reviewerRepository;

    @BeforeEach
    void cleanUp() {
        assignmentRepository.deleteAll();
        submissionRepository.deleteAll();
        reviewerRepository.deleteAll();
    }

    private String createReviewer(String no, String name, String affiliation, String topics,
                                  int maxConcurrent, boolean active) throws Exception {
        String body = """
                {"reviewerNo":"%s","name":"%s","affiliation":"%s","topics":%s,
                 "maxConcurrent":%d,"active":%s}
                """.formatted(no, name, affiliation, topics, maxConcurrent, active);
        String response = mockMvc.perform(post("/api/reviewers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return response.replaceAll(".*\"id\":(\\d+).*", "$1");
    }

    @Test
    void fullAssignDeclineAndQueryFlow() throws Exception {
        String reviewer1 = createReviewer("R1", "评审一", "北京大学", "[\"AI\"]", 2, true);
        String reviewer2 = createReviewer("R2", "评审二", "复旦大学", "[\"AI\"]", 2, true);

        String submissionResponse = mockMvc.perform(post("/api/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"manuscriptNo":"M-100","topics":["AI"],
                                 "authors":[{"name":"作者甲","affiliation":"清华大学"}],
                                 "requiredReviewers":1}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String submissionId = submissionResponse.replaceAll(".*\"id\":(\\d+).*", "$1");

        mockMvc.perform(post("/api/submissions/{id}/assign", submissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assignments", hasSize(1)))
                .andExpect(jsonPath("$.assignments[0].reviewerId").value(reviewer1))
                .andExpect(jsonPath("$.assignments[0].status").value("ACTIVE"));

        mockMvc.perform(get("/api/reviewers/{id}/load", reviewer1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeAssignments").value(1))
                .andExpect(jsonPath("$.remainingCapacity").value(1));

        mockMvc.perform(post("/api/submissions/{id}/assignments/{reviewerId}/decline",
                        submissionId, reviewer1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments", hasSize(2)))
                .andExpect(jsonPath("$.assignments[0].status").value("DECLINED"))
                .andExpect(jsonPath("$.assignments[1].reviewerId").value(reviewer2))
                .andExpect(jsonPath("$.assignments[1].status").value("ACTIVE"));

        mockMvc.perform(get("/api/submissions/{id}/assignments", submissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manuscriptNo").value("M-100"))
                .andExpect(jsonPath("$.activeAssignments").value(1));
    }

    @Test
    void assignReturns422WhenNotEnoughReviewers() throws Exception {
        createReviewer("R1", "评审一", "北京大学", "[\"AI\"]", 2, true);
        String submissionResponse = mockMvc.perform(post("/api/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"manuscriptNo":"M-200","topics":["AI"],
                                 "authors":[{"name":"作者甲","affiliation":"清华大学"}],
                                 "requiredReviewers":2}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String submissionId = submissionResponse.replaceAll(".*\"id\":(\\d+).*", "$1");

        mockMvc.perform(post("/api/submissions/{id}/assign", submissionId))
                .andExpect(status().isUnprocessableEntity());
    }
}
