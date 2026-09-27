package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.service.OverdueScanService;
import com.chris64233.cc.peerreview.web.dto.ScanResultDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 逾期扫描入口。幂等且可断点续扫，适合由外部调度器（cron 等）周期触发。
 */
@RestController
@RequestMapping("/api/overdue-scan")
public class OverdueScanController {

    private final OverdueScanService overdueScanService;

    public OverdueScanController(OverdueScanService overdueScanService) {
        this.overdueScanService = overdueScanService;
    }

    @PostMapping
    public ScanResultDto scan() {
        return overdueScanService.scanOverdue();
    }
}
