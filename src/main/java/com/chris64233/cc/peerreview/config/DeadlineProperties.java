package com.chris64233.cc.peerreview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 截止期与延期规则配置（前缀 review.deadline）。
 * roundDays：各评审轮次的基础天数；未配置的轮次使用 defaultRoundDays。
 * maxExtensionDays：编辑单次批准延期的天数上限，且每个任务最多批准一次。
 */
@ConfigurationProperties(prefix = "review.deadline")
public class DeadlineProperties {

    private Map<Integer, Integer> roundDays = new HashMap<>(Map.of(1, 21, 2, 14));

    private int defaultRoundDays = 10;

    private int maxExtensionDays = 14;

    public Map<Integer, Integer> getRoundDays() {
        return roundDays;
    }

    public void setRoundDays(Map<Integer, Integer> roundDays) {
        this.roundDays = roundDays;
    }

    public int getDefaultRoundDays() {
        return defaultRoundDays;
    }

    public void setDefaultRoundDays(int defaultRoundDays) {
        this.defaultRoundDays = defaultRoundDays;
    }

    public int getMaxExtensionDays() {
        return maxExtensionDays;
    }

    public void setMaxExtensionDays(int maxExtensionDays) {
        this.maxExtensionDays = maxExtensionDays;
    }

    public int roundDaysFor(int reviewRound) {
        return roundDays.getOrDefault(reviewRound, defaultRoundDays);
    }
}
