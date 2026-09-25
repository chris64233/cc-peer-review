package com.chris64233.cc.peerreview.web.dto;

import java.util.Set;

public record ReviewerDto(
        Long id,
        String code,
        String affiliation,
        Set<String> topics,
        int maxAssignments,
        boolean enabled) {
}
