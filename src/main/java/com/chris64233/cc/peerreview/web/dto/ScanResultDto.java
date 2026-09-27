package com.chris64233.cc.peerreview.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 一次逾期扫描的结果。扫描幂等：已处于终态或处理失败的任务留待下一批次。
 */
public record ScanResultDto(
        Instant scannedAt,
        int dueFound,
        int overdueMarked,
        int reassigned,
        int skipped,
        List<Long> overdueAssignmentIds) {
}
