package com.chris64233.cc.peerreview.web.dto;

import java.time.Instant;
import java.util.List;

public record AssignmentDto(
        Long assignmentId,
        String manuscriptNo,
        Long reviewerId,
        String reviewerCode,
        String reviewerAffiliation,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant dueAt,
        Integer reviewRound,
        Integer extensionDays,
        String deadlineBasis,
        String statusReason,
        Long replacesAssignmentId,
        List<ExtensionEventDto> extensionEvents) {
}
