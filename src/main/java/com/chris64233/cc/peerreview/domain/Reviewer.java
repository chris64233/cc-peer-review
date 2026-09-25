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
import jakarta.persistence.Version;

import java.util.HashSet;
import java.util.Set;

/**
 * 评审人：唯一编号、所属机构、擅长主题、最大同时评审数、启用状态。
 */
@Entity
@Table(name = "reviewer",
        uniqueConstraints = @UniqueConstraint(name = "uk_reviewer_code", columnNames = "code"))
public class Reviewer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String affiliation;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "reviewer_topic",
            joinColumns = @JoinColumn(name = "reviewer_id", nullable = false),
            uniqueConstraints = @UniqueConstraint(name = "uk_reviewer_topic",
                    columnNames = {"reviewer_id", "topic"}))
    @Column(name = "topic", nullable = false, length = 64)
    private Set<String> topics = new HashSet<>();

    @Column(name = "max_assignments", nullable = false)
    private int maxAssignments;

    @Column(nullable = false)
    private boolean enabled = true;

    @Version
    private Long version;

    protected Reviewer() {
    }

    public Reviewer(String code, String affiliation, Set<String> topics, int maxAssignments, boolean enabled) {
        this.code = code;
        this.affiliation = affiliation;
        this.topics = topics == null ? new HashSet<>() : new HashSet<>(topics);
        this.maxAssignments = maxAssignments;
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getAffiliation() {
        return affiliation;
    }

    public void setAffiliation(String affiliation) {
        this.affiliation = affiliation;
    }

    public Set<String> getTopics() {
        return topics;
    }

    public void setTopics(Set<String> topics) {
        this.topics = topics;
    }

    public int getMaxAssignments() {
        return maxAssignments;
    }

    public void setMaxAssignments(int maxAssignments) {
        this.maxAssignments = maxAssignments;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
