package com.chris64233.cc.peerreview.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewSubmitBody(
        @NotBlank @Size(max = 4000) String content) {
}
