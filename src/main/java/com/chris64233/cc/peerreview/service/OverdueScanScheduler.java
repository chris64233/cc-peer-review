package com.chris64233.cc.peerreview.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时触发逾期扫描。可用 review.deadline.scan-enabled=false 关闭（如测试环境），
 * 此时仍可通过 POST /api/admin/overdue-scan 手动触发。
 */
@Component
@ConditionalOnProperty(name = "review.deadline.scan-enabled", havingValue = "true", matchIfMissing = true)
public class OverdueScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(OverdueScanScheduler.class);

    private final OverdueScanService overdueScanService;

    public OverdueScanScheduler(OverdueScanService overdueScanService) {
        this.overdueScanService = overdueScanService;
    }

    @Scheduled(fixedDelayString = "${review.deadline.scan-interval-ms:60000}",
            initialDelayString = "${review.deadline.scan-initial-delay-ms:10000}")
    public void run() {
        var result = overdueScanService.scanOnce();
        if (result.processed() > 0) {
            log.info("逾期扫描完成：扫描 {} 条，处理 {} 条", result.scanned(), result.processed());
        }
    }
}
