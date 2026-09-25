package com.chris64233.cc.peerreview.web.dto;

import java.util.Set;

public record ReviewerView(Long id, String reviewerNo, String name, String affiliation,
                           Set<String> topics, int maxConcurrent, boolean active) {
}
