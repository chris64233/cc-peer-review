package com.chris64233.cc.peerreview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 评审任务生命周期事件（不可变审计日志）。
 * 只插不改不删：分配、延期申请/批准/拒绝、提交、迟到、逾期、改派均在此留痕。
 * 故意不建外键：迟到事件需要在主事务回滚后仍能独立提交（REQUIRES_NEW），
 * 若持有对 assignment 行的外键引用，新事务会与持有行锁的外层事务互相等待。
 */
@Entity
@Table(name = "assignment_event",
        indexes = {
                @Index(name = "idx_event_manuscript", columnList = "manuscript_no"),
                @Index(name = "idx_event_assignment", columnList = "assignment_id")
        })
public class AssignmentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "manuscript_no", nullable = false, length = 64)
    private String manuscriptNo;

    @Column(name = "reviewer_code", nullable = false, length = 64)
    private String reviewerCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AssignmentEventType type;

    /** 操作者：评审人编号、编辑编号或 system。 */
    @Column(nullable = false, length = 64)
    private String actor;

    @Column(nullable = false, length = 1024)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AssignmentEvent() {
    }

    public AssignmentEvent(Long assignmentId, String manuscriptNo, String reviewerCode,
                           AssignmentEventType type, String actor, String detail, Instant createdAt) {
        this.assignmentId = assignmentId;
        this.manuscriptNo = manuscriptNo;
        this.reviewerCode = reviewerCode;
        this.type = type;
        this.actor = actor;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public String getManuscriptNo() {
        return manuscriptNo;
    }

    public String getReviewerCode() {
        return reviewerCode;
    }

    public AssignmentEventType getType() {
        return type;
    }

    public String getActor() {
        return actor;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
