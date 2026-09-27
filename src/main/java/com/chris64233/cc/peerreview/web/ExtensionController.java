package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.service.ExtensionService;
import com.chris64233.cc.peerreview.web.dto.ExtensionDecisionBody;
import com.chris64233.cc.peerreview.web.dto.ExtensionEventDto;
import com.chris64233.cc.peerreview.web.dto.ExtensionRequestBody;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 延期申请与审批。所有操作只追加不可变事件；重复投递幂等。
 */
@RestController
@RequestMapping("/api/submissions/{manuscriptNo}/assignments/{reviewerCode}/extension")
public class ExtensionController {

    private final ExtensionService extensionService;

    public ExtensionController(ExtensionService extensionService) {
        this.extensionService = extensionService;
    }

    /**
     * 评审人提交延期申请。
     */
    @PostMapping("/requests")
    public List<ExtensionEventDto> request(@PathVariable String manuscriptNo,
                                           @PathVariable String reviewerCode,
                                           @Valid @RequestBody ExtensionRequestBody body) {
        return extensionService.request(manuscriptNo, reviewerCode, body.days(), body.reason());
    }

    /**
     * 编辑批准延期（每个任务限一次，天数不超过上限）。
     */
    @PostMapping("/approve")
    public List<ExtensionEventDto> approve(@PathVariable String manuscriptNo,
                                           @PathVariable String reviewerCode,
                                           @Valid @RequestBody ExtensionDecisionBody body) {
        return extensionService.approve(manuscriptNo, reviewerCode, body.editor(), body.days(), body.reason());
    }

    /**
     * 编辑拒绝延期。
     */
    @PostMapping("/reject")
    public List<ExtensionEventDto> reject(@PathVariable String manuscriptNo,
                                          @PathVariable String reviewerCode,
                                          @Valid @RequestBody ExtensionDecisionBody body) {
        return extensionService.reject(manuscriptNo, reviewerCode, body.editor(), body.reason());
    }

    /**
     * 该任务的延期事件时间线。
     */
    @GetMapping("/events")
    public List<ExtensionEventDto> events(@PathVariable String manuscriptNo,
                                          @PathVariable String reviewerCode) {
        return extensionService.listEvents(manuscriptNo, reviewerCode);
    }
}
