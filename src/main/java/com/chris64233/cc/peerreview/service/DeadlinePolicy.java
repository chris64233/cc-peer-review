package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.config.ReviewDeadlineProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 截止期策略：按稿件轮次计算基础天数（逐轮收紧、下限兜底），
 * 再与评审人个人评审周期取较小值；计算依据随结果一并返回，由调用方持久化。
 */
@Component
public class DeadlinePolicy {

    private final ReviewDeadlineProperties properties;

    public DeadlinePolicy(ReviewDeadlineProperties properties) {
        this.properties = properties;
    }

    /**
     * @param round        稿件评审轮次（>=1）
     * @param reviewerDays 评审人承诺的评审周期（天），0 表示不设个人规则
     * @param assignedAt   任务创建时间
     */
    public Computation compute(int round, int reviewerDays, Instant assignedAt) {
        int roundDays = roundDays(round);
        int effectiveDays = reviewerDays > 0 ? Math.min(roundDays, reviewerDays) : roundDays;
        Instant dueAt = assignedAt.plus(Duration.ofDays(effectiveDays));
        String basis = "round=%d, roundDays=%d, reviewerDays=%s, effectiveDays=%d, assignedAt=%s"
                .formatted(round, roundDays, reviewerDays > 0 ? reviewerDays : "-", effectiveDays, assignedAt);
        return new Computation(dueAt, basis);
    }

    /** 第 N 轮基础天数：首轮 firstRoundDays，每轮减 roundStepDays，不低于 minDays。 */
    public int roundDays(int round) {
        int days = properties.getFirstRoundDays() - (Math.max(1, round) - 1) * properties.getRoundStepDays();
        return Math.max(properties.getMinDays(), days);
    }

    public int extensionMaxDays() {
        return properties.getExtensionMaxDays();
    }

    /**
     * @param dueAt  计算得到的截止时间
     * @param basis  人类可读的计算依据，随任务持久化以便审计
     */
    public record Computation(Instant dueAt, String basis) {
    }
}
