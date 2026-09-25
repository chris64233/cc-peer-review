package com.chris64233.cc.peerreview.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record CreateReviewerRequest(
        @NotBlank String reviewerNo,
        @NotBlank String name,
        @NotBlank String affiliation,
        @NotEmpty Set<String> topics,
        @Min(1) int maxConcurrent,
        boolean active) {
}
