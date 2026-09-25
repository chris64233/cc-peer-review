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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "submissions", uniqueConstraints = @UniqueConstraint(columnNames = "manuscript_no"))
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manuscript_no", nullable = false)
    private String manuscriptNo;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "submission_topics", joinColumns = @JoinColumn(name = "submission_id"))
    @Column(name = "topic", nullable = false)
    private Set<String> topics = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "submission_authors", joinColumns = @JoinColumn(name = "submission_id"))
    private List<Author> authors = new ArrayList<>();

    @Column(name = "required_reviewers", nullable = false)
    private int requiredReviewers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionStatus status = SubmissionStatus.PENDING;

    protected Submission() {
    }

    public Submission(String manuscriptNo, Set<String> topics, List<Author> authors, int requiredReviewers) {
        this.manuscriptNo = manuscriptNo;
        this.topics = new LinkedHashSet<>(topics);
        this.authors = new ArrayList<>(authors);
        this.requiredReviewers = requiredReviewers;
    }

    public Long getId() {
        return id;
    }

    public String getManuscriptNo() {
        return manuscriptNo;
    }

    public Set<String> getTopics() {
        return topics;
    }

    public List<Author> getAuthors() {
        return authors;
    }

    public int getRequiredReviewers() {
        return requiredReviewers;
    }

    public SubmissionStatus getStatus() {
        return status;
    }

    public void setStatus(SubmissionStatus status) {
        this.status = status;
    }
}
