package com.chris64233.cc.peerreview.web.dto;

import com.chris64233.cc.peerreview.domain.SubmissionStatus;

import java.util.List;
import java.util.Set;

public record SubmissionView(Long id, String manuscriptNo, Set<String> topics,
                             List<AuthorView> authors, int requiredReviewers, SubmissionStatus status) {

    public record AuthorView(String name, String affiliation) {
    }
}
