package com.chris64233.cc.peerreview.web.dto;

public record ReviewerLoadView(Long reviewerId, String reviewerNo, String name,
                               long activeAssignments, int maxConcurrent, long remainingCapacity) {
}
