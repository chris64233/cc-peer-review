package com.chris64233.cc.peerreview.web.dto;

import java.time.Instant;

/**
 * 一次改派：旧任务（拒绝或逾期）→ 替补任务。
 */
public record ReassignmentDto(
        Long fromAssignmentId,
        String fromReviewerCode,
        Long toAssignmentId,
        String toReviewerCode,
        String reason,
        Instant at) {
}
