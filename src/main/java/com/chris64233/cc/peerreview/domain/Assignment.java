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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 评审分配。同一稿件对同一评审人只允许存在一行（含已拒绝），
 * 由数据库唯一约束兜底，保证拒绝过的评审人不会被再次分配给同一稿件。
 */
@Entity
@Table(name = "assignment",
        uniqueConstraints = @UniqueConstraint(name = "uk_assignment_submission_reviewer",
                columnNames = {"submission_id", "reviewer_id"}))
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private Reviewer reviewer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AssignmentStatus status = AssignmentStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Assignment() {
    }

    public Assignment(Submission submission, Reviewer reviewer, AssignmentStatus status, Instant when) {
        this.submission = submission;
        this.reviewer = reviewer;
        this.status = status;
        this.createdAt = when;
        this.updatedAt = when;
    }

    public Long getId() {
        return id;
    }

    public Submission getSubmission() {
        return submission;
    }

    public Reviewer getReviewer() {
        return reviewer;
    }

    public AssignmentStatus getStatus() {
        return status;
    }

    public void setStatus(AssignmentStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
