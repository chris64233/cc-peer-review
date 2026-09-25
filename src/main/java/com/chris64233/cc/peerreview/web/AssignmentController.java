package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.service.AssignmentService;
import com.chris64233.cc.peerreview.web.dto.SubmissionAssignmentDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/submissions/{manuscriptNo}")
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    /**
     * 进入评审后自动选择一组评审人。
     */
    @PostMapping("/assignments/auto")
    public SubmissionAssignmentDto autoAssign(@PathVariable String manuscriptNo) {
        return assignmentService.autoAssign(manuscriptNo);
    }

    /**
     * 评审人拒绝任务，原子完成拒绝与替补选择。
     */
    @PostMapping("/assignments/{reviewerCode}/decline")
    public SubmissionAssignmentDto decline(@PathVariable String manuscriptNo,
                                           @PathVariable String reviewerCode) {
        return assignmentService.decline(manuscriptNo, reviewerCode);
    }

    /**
     * 投稿分配详情。
     */
    @GetMapping("/assignments")
    public SubmissionAssignmentDto detail(@PathVariable String manuscriptNo) {
        return assignmentService.getDetail(manuscriptNo);
    }
}
