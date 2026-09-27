package com.chris64233.cc.peerreview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 延期事件（申请/批准/拒绝），只增不改的审计日志。
 * 实体不提供任何 setter，也没有更新/删除入口，保证事件不可变；
 * 任务的当前延期状态由事件序列推导（最近一次 REQUESTED 之后是否有 APPROVED/REJECTED）。
 */
@Entity
@Table(name = "extension_event")
public class ExtensionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false)
    private Assignment assignment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExtensionEventType type;

    /** 申请或批准的天数；拒绝事件为 null。 */
    @Column(name = "days")
    private Integer days;

    @Column(name = "reason", length = 512)
    private String reason;

    /** 操作者：申请人为评审人编号，审批人为编辑编号。 */
    @Column(name = "actor", nullable = false, length = 64)
    private String actor;

    /** 批准前的截止时间（仅 APPROVED）。 */
    @Column(name = "previous_due_at")
    private Instant previousDueAt;

    /** 批准后的截止时间（仅 APPROVED）。 */
    @Column(name = "resulting_due_at")
    private Instant resultingDueAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExtensionEvent() {
    }

    public ExtensionEvent(Assignment assignment, ExtensionEventType type, Integer days,
                          String reason, String actor,
                          Instant previousDueAt, Instant resultingDueAt, Instant createdAt) {
        this.assignment = assignment;
        this.type = type;
        this.days = days;
        this.reason = reason;
        this.actor = actor;
        this.previousDueAt = previousDueAt;
        this.resultingDueAt = resultingDueAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Assignment getAssignment() {
        return assignment;
    }

    public ExtensionEventType getType() {
        return type;
    }

    public Integer getDays() {
        return days;
    }

    public String getReason() {
        return reason;
    }

    public String getActor() {
        return actor;
    }

    public Instant getPreviousDueAt() {
        return previousDueAt;
    }

    public Instant getResultingDueAt() {
        return resultingDueAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
