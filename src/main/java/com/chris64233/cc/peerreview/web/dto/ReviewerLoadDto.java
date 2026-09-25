package com.chris64233.cc.peerreview.web.dto;

public record ReviewerLoadDto(
        Long reviewerId,
        String code,
        String affiliation,
        int maxAssignments,
        long activeAssignments,
        long remainingCapacity) {
}
