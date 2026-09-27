package com.chris64233.cc.peerreview.web.dto;

import java.time.Instant;

public record AssignmentEventDto(
        Long eventId,
        Long assignmentId,
        String reviewerCode,
        String type,
        String actor,
        String detail,
        Instant createdAt) {
}
