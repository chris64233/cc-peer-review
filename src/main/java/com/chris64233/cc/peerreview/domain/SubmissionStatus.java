package com.chris64233.cc.peerreview.domain;

/**
 * 稿件评审状态。
 * PENDING：尚未凑齐评审人（含从未分配或分配整体失败）；
 * ASSIGNED：有效评审人数已达到要求人数；
 * SHORT：曾成功分配但因拒绝导致有效评审人数不足，且无可用替补。
 */
public enum SubmissionStatus {
    PENDING,
    ASSIGNED,
    SHORT
}
