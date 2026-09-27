package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentEvent;
import com.chris64233.cc.peerreview.domain.AssignmentEventType;
import com.chris64233.cc.peerreview.repository.AssignmentEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 事件写入服务。事件是不可变审计日志：正常流程随主事务一起提交（与状态变更原子），
 * 迟到意见等需要"主事务回滚后仍留痕"的场景使用独立事务。
 */
@Service
public class AssignmentEventService {

    private final AssignmentEventRepository eventRepository;

    public AssignmentEventService(AssignmentEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** 随当前事务提交：状态变更与事件要么都成功，要么都回滚。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public AssignmentEvent record(Assignment assignment, AssignmentEventType type,
                                  String actor, String detail, Instant when) {
        return eventRepository.save(new AssignmentEvent(
                assignment.getId(),
                assignment.getSubmission().getManuscriptNo(),
                assignment.getReviewer().getCode(),
                type, actor, detail, when));
    }

    /**
     * 独立事务提交：即使调用方随后抛异常回滚（如迟到意见被拒绝），事件仍然保留。
     * 事件表不建外键，避免新事务与持有行锁的外层事务相互等待。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AssignmentEvent recordIndependent(Assignment assignment, AssignmentEventType type,
                                             String actor, String detail, Instant when) {
        return record(assignment, type, actor, detail, when);
    }
}
