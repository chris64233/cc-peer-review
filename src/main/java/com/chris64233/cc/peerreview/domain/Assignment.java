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
 * 截止期在创建时按稿件轮次与评审人规则计算，计算依据随任务持久化。
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

    /** 评审意见提交截止时间；为空表示不参与逾期扫描（历史数据）。 */
    @Column(name = "due_at")
    private Instant dueAt;

    /** 截止期计算依据（轮次、轮次天数、评审人规则天数、生效天数、起始时间），批准延期时追加。 */
    @Column(name = "deadline_basis", length = 512)
    private String deadlineBasis;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "review_comment", length = 2000)
    private String reviewComment;

    @Column(name = "overdue_reason", length = 512)
    private String overdueReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Assignment() {
    }

    public Assignment(Submission submission, Reviewer reviewer, AssignmentStatus status, Instant when) {
        this(submission, reviewer, status, when, null, null);
    }

    public Assignment(Submission submission, Reviewer reviewer, AssignmentStatus status, Instant when,
                      Instant dueAt, String deadlineBasis) {
        this.submission = submission;
        this.reviewer = reviewer;
        this.status = status;
        this.createdAt = when;
        this.updatedAt = when;
        this.dueAt = dueAt;
        this.deadlineBasis = deadlineBasis;
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

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public String getDeadlineBasis() {
        return deadlineBasis;
    }

    public void setDeadlineBasis(String deadlineBasis) {
        this.deadlineBasis = deadlineBasis;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public void setReviewComment(String reviewComment) {
        this.reviewComment = reviewComment;
    }

    public String getOverdueReason() {
        return overdueReason;
    }

    public void setOverdueReason(String overdueReason) {
        this.overdueReason = overdueReason;
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
