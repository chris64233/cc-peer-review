package com.chris64233.cc.peerreview.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Set;

public record CreateSubmissionRequest(
        @NotBlank String manuscriptNo,
        @NotEmpty Set<String> topics,
        @NotEmpty List<@Valid AuthorRequest> authors,
        @Min(1) int requiredReviewers) {

    public record AuthorRequest(@NotBlank String name, @NotBlank String affiliation) {
    }
}
