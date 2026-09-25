package com.chris64233.cc.peerreview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class Author {

    @Column(name = "author_name", nullable = false)
    private String name;

    @Column(name = "author_affiliation", nullable = false)
    private String affiliation;

    protected Author() {
    }

    public Author(String name, String affiliation) {
        this.name = name;
        this.affiliation = affiliation;
    }

    public String getName() {
        return name;
    }

    public String getAffiliation() {
        return affiliation;
    }
}
