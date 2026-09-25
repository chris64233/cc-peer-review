package com.chris64233.cc.peerreview.web.dto;

import java.time.Instant;

public record AssignmentDto(
        Long assignmentId,
        String manuscriptNo,
        Long reviewerId,
        String reviewerCode,
        String reviewerAffiliation,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
