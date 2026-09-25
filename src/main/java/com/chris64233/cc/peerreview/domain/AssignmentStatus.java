package com.chris64233.cc.peerreview.domain;

/**
 * 评审任务状态。
 * ACTIVE：有效分配，占用评审人工作量；
 * DECLINED：评审人已拒绝，不再占用工作量，但记录保留以禁止该评审人再次被分配给同一稿件。
 */
public enum AssignmentStatus {
    ACTIVE,
    DECLINED
}
