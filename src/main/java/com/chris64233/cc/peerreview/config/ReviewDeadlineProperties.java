package com.chris64233.cc.peerreview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 评审截止期与逾期扫描配置。
 * firstRoundDays：第 1 轮评审的基础天数；
 * roundStepDays：每进入下一轮缩短的天数；
 * minDays：任何轮次的最短评审天数（下限兜底）；
 * extensionMaxDays：编辑单次批准延期的天数上限（每个任务至多批准一次）；
 * scanBatchSize：逾期扫描单批处理的任务数，剩余任务留给下一批，保证重启后可继续。
 */
@ConfigurationProperties(prefix = "review.deadline")
public class ReviewDeadlineProperties {

    private int firstRoundDays = 21;
    private int roundStepDays = 7;
    private int minDays = 7;
    private int extensionMaxDays = 14;
    private int scanBatchSize = 100;

    public int getFirstRoundDays() {
        return firstRoundDays;
    }

    public void setFirstRoundDays(int firstRoundDays) {
        this.firstRoundDays = firstRoundDays;
    }

    public int getRoundStepDays() {
        return roundStepDays;
    }

    public void setRoundStepDays(int roundStepDays) {
        this.roundStepDays = roundStepDays;
    }

    public int getMinDays() {
        return minDays;
    }

    public void setMinDays(int minDays) {
        this.minDays = minDays;
    }

    public int getExtensionMaxDays() {
        return extensionMaxDays;
    }

    public void setExtensionMaxDays(int extensionMaxDays) {
        this.extensionMaxDays = extensionMaxDays;
    }

    public int getScanBatchSize() {
        return scanBatchSize;
    }

    public void setScanBatchSize(int scanBatchSize) {
        this.scanBatchSize = scanBatchSize;
    }
}
