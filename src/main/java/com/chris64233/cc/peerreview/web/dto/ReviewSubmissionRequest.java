package com.chris64233.cc.peerreview.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ReviewSubmissionRequest(
        @NotBlank String comment) {
}
