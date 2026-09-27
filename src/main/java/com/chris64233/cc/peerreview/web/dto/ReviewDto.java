package com.chris64233.cc.peerreview.web.dto;

import java.time.Instant;

/**
 * 评审意见。late=true 表示意见到达时任务已进入终态（如已逾期改派），仅存档不恢复任务。
 */
public record ReviewDto(
        Long reviewId,
        Long assignmentId,
        String manuscriptNo,
        String reviewerCode,
        boolean late,
        String content,
        Instant submittedAt) {
}
