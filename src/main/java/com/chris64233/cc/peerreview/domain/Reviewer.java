package com.chris64233.cc.peerreview.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "reviewers", uniqueConstraints = @UniqueConstraint(columnNames = "reviewer_no"))
public class Reviewer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reviewer_no", nullable = false)
    private String reviewerNo;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String affiliation;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "reviewer_topics", joinColumns = @JoinColumn(name = "reviewer_id"))
    @Column(name = "topic", nullable = false)
    private Set<String> topics = new LinkedHashSet<>();

    @Column(name = "max_concurrent", nullable = false)
    private int maxConcurrent;

    @Column(nullable = false)
    private boolean active;

    protected Reviewer() {
    }

    public Reviewer(String reviewerNo, String name, String affiliation, Set<String> topics,
                    int maxConcurrent, boolean active) {
        this.reviewerNo = reviewerNo;
        this.name = name;
        this.affiliation = affiliation;
        this.topics = new LinkedHashSet<>(topics);
        this.maxConcurrent = maxConcurrent;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public String getReviewerNo() {
        return reviewerNo;
    }

    public String getName() {
        return name;
    }

    public String getAffiliation() {
        return affiliation;
    }

    public Set<String> getTopics() {
        return topics;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public boolean isActive() {
        return active;
    }
}
