package com.chris64233.cc.peerreview.web.dto;

import java.util.Set;

public record SubmissionDto(
        Long id,
        String manuscriptNo,
        Set<String> topics,
        Set<AuthorRequest> authors,
        int requiredReviewers,
        String status) {
}
