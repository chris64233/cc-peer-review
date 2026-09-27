package com.chris64233.cc.peerreview.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ExtensionRejectionRequest(
        @NotBlank String decidedBy,
        String reason) {
}
