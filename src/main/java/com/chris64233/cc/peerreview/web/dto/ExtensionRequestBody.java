package com.chris64233.cc.peerreview.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 评审人延期申请。days 必须在上限内（上限由服务端配置复核，此处先做基本校验）。
 */
public record ExtensionRequestBody(
        @NotNull @Min(1) @Max(365) Integer days,
        String reason) {
}
