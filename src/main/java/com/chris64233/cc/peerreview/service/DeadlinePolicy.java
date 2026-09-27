package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.config.DeadlineProperties;
import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.domain.Submission;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 截止期计算策略：取“稿件轮次基础天数”和“评审人承诺周转天数”的较大者，
 * 即评审人规则只能延长、不能压缩轮次要求。计算结果连同全部输入快照保存到分配记录上。
 */
@Component
public class DeadlinePolicy {

    private final DeadlineProperties properties;

    public DeadlinePolicy(DeadlineProperties properties) {
        this.properties = properties;
    }

    public DeadlineComputation compute(Submission submission, Reviewer reviewer, Instant assignedAt) {
        int round = submission.getReviewRound();
        int roundDays = properties.roundDaysFor(round);
        int reviewerDays = reviewer.getReviewDays();
        int days = Math.max(roundDays, reviewerDays);
        return new DeadlineComputation(
                round, roundDays, reviewerDays, days,
                assignedAt.plus(days, ChronoUnit.DAYS),
                basis(round, roundDays, reviewerDays, 0));
    }

    /**
     * 计算依据的可读描述；延期批准后以最新的 extensionDays 重新生成。
     */
    public String basis(int round, int roundDays, int reviewerDays, int extensionDays) {
        return "round=" + round
                + ";roundDays=" + roundDays
                + ";reviewerDays=" + reviewerDays
                + ";extensionDays=" + extensionDays;
    }

    public int maxExtensionDays() {
        return properties.getMaxExtensionDays();
    }

    /**
     * @param dueAt 含已批准延期后的当前截止时间
     */
    public record DeadlineComputation(int reviewRound, int roundDays, int reviewerDays,
                                      int totalDays, Instant dueAt, String basis) {
    }
}
