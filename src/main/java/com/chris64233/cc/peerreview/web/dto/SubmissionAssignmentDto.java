package com.chris64233.cc.peerreview.web.dto;

import java.util.List;

public record SubmissionAssignmentDto(
        String manuscriptNo,
        String submissionStatus,
        int requiredReviewers,
        long activeReviewers,
        List<AssignmentDto> assignments) {
}
