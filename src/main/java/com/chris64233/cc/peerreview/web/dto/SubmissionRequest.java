package com.chris64233.cc.peerreview.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record SubmissionRequest(
        @NotBlank String manuscriptNo,
        @NotEmpty Set<String> topics,
        @NotEmpty @Valid Set<AuthorRequest> authors,
        @Min(1) int requiredReviewers) {
}
