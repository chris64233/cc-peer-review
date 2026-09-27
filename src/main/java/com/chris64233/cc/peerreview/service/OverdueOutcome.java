package com.chris64233.cc.peerreview.service;

/**
 * 单条逾期任务的处理结果。
 * SKIPPED：任务已处于终态或截止期已被并发延期顺延，本次无副作用（幂等）；
 * OVERDUE_REASSIGNED：已转逾期并原子补选替补；
 * OVERDUE_NO_REPLACEMENT：已转逾期但无可用替补，稿件进入缺员。
 */
public enum OverdueOutcome {
    SKIPPED,
    OVERDUE_REASSIGNED,
    OVERDUE_NO_REPLACEMENT
}
