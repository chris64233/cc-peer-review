package com.chris64233.cc.peerreview.domain;

/**
 * 延期事件类型。事件一旦写入即不可变，审批结果通过追加新事件表达。
 * REQUESTED：评审人提交延期申请；
 * APPROVED：编辑批准延期（截止期顺延）；
 * REJECTED：编辑拒绝延期（截止期不变）。
 */
public enum ExtensionEventType {
    REQUESTED,
    APPROVED,
    REJECTED
}
