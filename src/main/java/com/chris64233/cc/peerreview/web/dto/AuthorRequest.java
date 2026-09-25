package com.chris64233.cc.peerreview.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AuthorRequest(
        @NotBlank String code,
        @NotBlank String affiliation) {
}
