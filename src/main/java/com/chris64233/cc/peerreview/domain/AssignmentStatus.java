package com.chris64233.cc.peerreview.domain;

/**
 * 评审任务状态。
 * ACTIVE：有效分配，占用评审人工作量，是唯一能发生状态变迁的非终态；
 * DECLINED：评审人已拒绝，不再占用工作量，但记录保留以禁止该评审人再次被分配给同一稿件；
 * SUBMITTED：评审意见已提交（终态），不再占用工作量，不可被改派；
 * OVERDUE：截止期到达仍未提交（终态），不再占用工作量，迟到意见不能恢复任务。
 */
public enum AssignmentStatus {
    ACTIVE,
    DECLINED,
    SUBMITTED,
    OVERDUE
}
