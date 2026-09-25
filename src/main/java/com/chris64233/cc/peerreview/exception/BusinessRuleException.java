package com.chris64233.cc.peerreview.exception;

/**
 * 业务规则失败（如候选评审人不足）。触发事务回滚，保证整笔分配失败。
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
