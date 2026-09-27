package com.chris64233.cc.peerreview.domain;

/**
 * 评审任务状态。
 * ACTIVE：有效分配，占用评审人工作量；
 * DECLINED：评审人已拒绝，不再占用工作量，但记录保留以禁止该评审人再次被分配给同一稿件；
 * COMPLETED：评审意见已按期提交，终态，不再占用工作量，不可被改派；
 * OVERDUE：截止期到达仍未提交，终态，负载已释放并触发改派，迟交意见不得恢复任务。
 */
public enum AssignmentStatus {
    ACTIVE,
    DECLINED,
    COMPLETED,
    OVERDUE
}
