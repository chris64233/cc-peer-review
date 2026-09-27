package com.chris64233.cc.peerreview.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ExtensionRequestBody(
        @NotNull @Min(1) Integer requestedDays,
        String reason,
        @NotBlank String requestedBy) {
}
