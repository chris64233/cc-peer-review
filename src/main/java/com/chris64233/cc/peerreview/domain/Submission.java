package com.chris64233.cc.peerreview.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.util.HashSet;
import java.util.Set;

/**
 * 投稿：唯一稿件号、主题集合、作者（含所属机构）、要求评审人数。
 */
@Entity
@Table(name = "submission",
        uniqueConstraints = @UniqueConstraint(name = "uk_submission_manuscript_no",
                columnNames = "manuscript_no"))
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manuscript_no", nullable = false, length = 64)
    private String manuscriptNo;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "submission_topic",
            joinColumns = @JoinColumn(name = "submission_id", nullable = false),
            uniqueConstraints = @UniqueConstraint(name = "uk_submission_topic",
                    columnNames = {"submission_id", "topic"}))
    @Column(name = "topic", nullable = false, length = 64)
    private Set<String> topics = new HashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "submission_author",
            joinColumns = @JoinColumn(name = "submission_id", nullable = false),
            uniqueConstraints = @UniqueConstraint(name = "uk_submission_author",
                    columnNames = {"submission_id", "author_code"}))
    private Set<Author> authors = new HashSet<>();

    @Column(name = "required_reviewers", nullable = false)
    private int requiredReviewers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SubmissionStatus status = SubmissionStatus.PENDING;

    @Version
    private Long version;

    protected Submission() {
    }

    public Submission(String manuscriptNo, Set<String> topics, Set<Author> authors, int requiredReviewers) {
        this.manuscriptNo = manuscriptNo;
        this.topics = topics == null ? new HashSet<>() : new HashSet<>(topics);
        this.authors = authors == null ? new HashSet<>() : new HashSet<>(authors);
        this.requiredReviewers = requiredReviewers;
    }

    public Long getId() {
        return id;
    }

    public String getManuscriptNo() {
        return manuscriptNo;
    }

    public void setManuscriptNo(String manuscriptNo) {
        this.manuscriptNo = manuscriptNo;
    }

    public Set<String> getTopics() {
        return topics;
    }

    public void setTopics(Set<String> topics) {
        this.topics = topics;
    }

    public Set<Author> getAuthors() {
        return authors;
    }

    public void setAuthors(Set<Author> authors) {
        this.authors = authors;
    }

    public int getRequiredReviewers() {
        return requiredReviewers;
    }

    public void setRequiredReviewers(int requiredReviewers) {
        this.requiredReviewers = requiredReviewers;
    }

    public SubmissionStatus getStatus() {
        return status;
    }

    public void setStatus(SubmissionStatus status) {
        this.status = status;
    }
}
