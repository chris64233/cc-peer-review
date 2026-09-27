package com.chris64233.cc.peerreview.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ExtensionApprovalRequest(
        @NotNull @Min(1) Integer approvedDays,
        @NotBlank String decidedBy) {
}
