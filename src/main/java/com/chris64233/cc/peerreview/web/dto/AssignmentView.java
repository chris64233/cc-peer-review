package com.chris64233.cc.peerreview.web.dto;

import com.chris64233.cc.peerreview.domain.AssignmentStatus;

import java.time.Instant;

public record AssignmentView(Long assignmentId, Long reviewerId, String reviewerNo,
                             String reviewerName, AssignmentStatus status, Instant createdAt) {
}
