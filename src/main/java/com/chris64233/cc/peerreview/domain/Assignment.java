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

import java.time.Instant;

@Entity
@Table(name = "assignments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"submission_id", "reviewer_id"}))
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private Reviewer reviewer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssignmentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Assignment() {
    }

    public Assignment(Submission submission, Reviewer reviewer) {
        this.submission = submission;
        this.reviewer = reviewer;
        this.status = AssignmentStatus.ACTIVE;
        this.createdAt = Instant.now();
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void decline() {
        this.status = AssignmentStatus.DECLINED;
    }
}
