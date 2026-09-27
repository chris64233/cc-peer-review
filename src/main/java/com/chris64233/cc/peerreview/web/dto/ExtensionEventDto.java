package com.chris64233.cc.peerreview.web.dto;

import java.time.Instant;

/**
 * 延期事件（不可变）。type 取值 REQUESTED / APPROVED / REJECTED；
 * previousDueAt、resultingDueAt 仅在 APPROVED 事件上有值，用于展示截止期变化。
 */
public record ExtensionEventDto(
        Long eventId,
        Long assignmentId,
        String type,
        Integer days,
        String reason,
        String actor,
        Instant previousDueAt,
        Instant resultingDueAt,
        Instant createdAt) {
}
