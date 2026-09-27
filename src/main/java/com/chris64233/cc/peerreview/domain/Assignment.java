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
 * 评审分配。同一稿件对同一评审人只允许存在一行（含已拒绝/已逾期），
 * 由数据库唯一约束兜底，保证被拒绝或逾期改派过的评审人不会被再次分配给同一稿件。
 *
 * 截止期相关字段在创建分配时按“稿件轮次 + 评审人规则”计算并快照保存（计算依据），
 * 延期只追加 extensionDays 并顺延 dueAt，原始快照不变，便于审计。
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

    /** 分配时稿件所处评审轮次（快照）。 */
    @Column(name = "review_round")
    private Integer reviewRound;

    /** 计算依据快照：该轮次基础天数。 */
    @Column(name = "round_days")
    private Integer roundDays;

    /** 计算依据快照：评审人承诺周转天数。 */
    @Column(name = "reviewer_days")
    private Integer reviewerDays;

    /** 已批准的延期天数（0 表示未延期，最多批准一次）。 */
    @Column(name = "extension_days", nullable = false)
    private int extensionDays = 0;

    /** 当前截止时间（含已批准延期）。 */
    @Column(name = "due_at")
    private Instant dueAt;

    /** 截止期计算依据的可读描述，随延期批准同步更新。 */
    @Column(name = "deadline_basis", length = 256)
    private String deadlineBasis;

    /** 终态原因说明（拒绝/逾期等），用于查询展示。 */
    @Column(name = "status_reason", length = 256)
    private String statusReason;

    /** 改派链：本任务替补了哪一条旧任务（拒绝或逾期产生）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaces_assignment_id")
    private Assignment replacesAssignment;

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

    public Integer getReviewRound() {
        return reviewRound;
    }

    public void setReviewRound(Integer reviewRound) {
        this.reviewRound = reviewRound;
    }

    public Integer getRoundDays() {
        return roundDays;
    }

    public void setRoundDays(Integer roundDays) {
        this.roundDays = roundDays;
    }

    public Integer getReviewerDays() {
        return reviewerDays;
    }

    public void setReviewerDays(Integer reviewerDays) {
        this.reviewerDays = reviewerDays;
    }

    public int getExtensionDays() {
        return extensionDays;
    }

    public void setExtensionDays(int extensionDays) {
        this.extensionDays = extensionDays;
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

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public Assignment getReplacesAssignment() {
        return replacesAssignment;
    }

    public void setReplacesAssignment(Assignment replacesAssignment) {
        this.replacesAssignment = replacesAssignment;
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
