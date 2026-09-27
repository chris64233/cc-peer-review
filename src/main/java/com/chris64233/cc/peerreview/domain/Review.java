package com.chris64233.cc.peerreview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 评审意见。每个分配任务最多一条（数据库唯一约束兜底，重复提交幂等返回已有记录）。
 * late=true 表示意见到达时任务已进入终态（如已逾期改派），仅存档，不得恢复任务。
 */
@Entity
@Table(name = "review",
        uniqueConstraints = @UniqueConstraint(name = "uk_review_assignment",
                columnNames = "assignment_id"))
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false)
    private Assignment assignment;

    @Column(name = "content", nullable = false, length = 4000)
    private String content;

    @Column(name = "late", nullable = false)
    private boolean late;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    protected Review() {
    }

    public Review(Assignment assignment, String content, boolean late, Instant submittedAt) {
        this.assignment = assignment;
        this.content = content;
        this.late = late;
        this.submittedAt = submittedAt;
    }

    public Long getId() {
        return id;
    }

    public Assignment getAssignment() {
        return assignment;
    }

    public String getContent() {
        return content;
    }

    public boolean isLate() {
        return late;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}
