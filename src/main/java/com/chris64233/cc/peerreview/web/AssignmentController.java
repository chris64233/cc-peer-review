package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.service.AssignmentService;
import com.chris64233.cc.peerreview.web.dto.ExtensionApprovalRequest;
import com.chris64233.cc.peerreview.web.dto.ExtensionRejectionRequest;
import com.chris64233.cc.peerreview.web.dto.ExtensionRequestBody;
import com.chris64233.cc.peerreview.web.dto.ReviewSubmissionRequest;
import com.chris64233.cc.peerreview.web.dto.SubmissionAssignmentDto;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
     * 评审人提交评审意见；任务已进入终态时迟到意见不被接受（409）。
     */
    @PostMapping("/assignments/{reviewerCode}/review")
    public SubmissionAssignmentDto submitReview(@PathVariable String manuscriptNo,
                                                @PathVariable String reviewerCode,
                                                @Valid @RequestBody ReviewSubmissionRequest request) {
        return assignmentService.submitReview(manuscriptNo, reviewerCode, request.comment());
    }

    /**
     * 评审人申请延期（天数不得超过上限）。
     */
    @PostMapping("/assignments/{reviewerCode}/extensions")
    public SubmissionAssignmentDto requestExtension(@PathVariable String manuscriptNo,
                                                    @PathVariable String reviewerCode,
                                                    @Valid @RequestBody ExtensionRequestBody request) {
        return assignmentService.requestExtension(manuscriptNo, reviewerCode, request);
    }

    /**
     * 编辑批准延期：每个任务至多一次，截止时间在当前基础上顺延。
     */
    @PostMapping("/assignments/{reviewerCode}/extensions/approve")
    public SubmissionAssignmentDto approveExtension(@PathVariable String manuscriptNo,
                                                    @PathVariable String reviewerCode,
                                                    @Valid @RequestBody ExtensionApprovalRequest request) {
        return assignmentService.approveExtension(manuscriptNo, reviewerCode, request);
    }

    /**
     * 编辑拒绝延期申请。
     */
    @PostMapping("/assignments/{reviewerCode}/extensions/reject")
    public SubmissionAssignmentDto rejectExtension(@PathVariable String manuscriptNo,
                                                   @PathVariable String reviewerCode,
                                                   @Valid @RequestBody ExtensionRejectionRequest request) {
        return assignmentService.rejectExtension(manuscriptNo, reviewerCode, request);
    }

    /**
     * 投稿分配详情：当前有效评审人、历次改派、截止期变化、逾期原因与全部生命周期事件。
     */
    @GetMapping("/assignments")
    public SubmissionAssignmentDto detail(@PathVariable String manuscriptNo) {
        return assignmentService.getDetail(manuscriptNo);
    }
}
