package com.chris64233.cc.peerreview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

/**
 * 作者信息（值对象）。code 为作者唯一标识，与评审人编号一致时视为同一人。
 */
@Embeddable
public class Author {

    @Column(name = "author_code", nullable = false, length = 64)
    private String code;

    @Column(name = "author_affiliation", nullable = false, length = 128)
    private String affiliation;

    protected Author() {
    }

    public Author(String code, String affiliation) {
        this.code = code;
        this.affiliation = affiliation;
    }

    public String getCode() {
        return code;
    }

    public String getAffiliation() {
        return affiliation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Author author)) {
            return false;
        }
        return Objects.equals(code, author.code) && Objects.equals(affiliation, author.affiliation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, affiliation);
    }
}
