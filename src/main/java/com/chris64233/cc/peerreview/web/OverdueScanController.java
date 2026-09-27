package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.service.OverdueScanService;
import com.chris64233.cc.peerreview.web.dto.OverdueScanResultDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class OverdueScanController {

    private final OverdueScanService overdueScanService;

    public OverdueScanController(OverdueScanService overdueScanService) {
        this.overdueScanService = overdueScanService;
    }

    /**
     * 手动触发一轮逾期扫描（幂等，可重复调用；定时扫描关闭时也可使用）。
     */
    @PostMapping("/overdue-scan")
    public OverdueScanResultDto scan() {
        return overdueScanService.scanOnce();
    }
}
