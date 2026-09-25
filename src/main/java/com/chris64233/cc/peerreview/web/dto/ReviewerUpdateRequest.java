package com.chris64233.cc.peerreview.web.dto;

import jakarta.validation.constraints.Min;

import java.util.Set;

public record ReviewerUpdateRequest(
        String affiliation,
        Set<String> topics,
        @Min(1) Integer maxAssignments,
        Boolean enabled) {
}
