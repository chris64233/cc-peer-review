package com.chris64233.cc.peerreview.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 编辑审批延期。批准时 days 为空表示按申请天数批准；拒绝时忽略 days。
 */
public record ExtensionDecisionBody(
        @NotBlank String editor,
        @Min(1) Integer days,
        String reason) {
}
