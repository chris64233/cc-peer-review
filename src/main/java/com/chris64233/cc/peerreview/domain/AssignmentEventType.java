package com.chris64233.cc.peerreview.domain;

/**
 * 评审任务生命周期事件类型。事件一旦写入不可修改、不可删除，
 * 用于还原截止期变化、延期审批、逾期与改派的完整历史。
 */
public enum AssignmentEventType {
    /** 任务创建（自动分配或替补改派）。 */
    ASSIGNED,
    /** 评审人拒绝任务。 */
    DECLINED,
    /** 评审人提出延期申请。 */
    EXTENSION_REQUESTED,
    /** 编辑批准延期（每个任务至多一次，且不超过配置上限）。 */
    EXTENSION_APPROVED,
    /** 编辑拒绝延期申请。 */
    EXTENSION_REJECTED,
    /** 评审意见按时提交。 */
    SUBMITTED,
    /** 任务进入终态后收到的迟到意见：仅留痕，不改变任务状态。 */
    LATE_SUBMITTED,
    /** 截止期到达未提交，任务转为逾期。 */
    OVERDUE,
    /** 逾期任务被改派给替补评审人（记录在原任务上）。 */
    REASSIGNED
}
