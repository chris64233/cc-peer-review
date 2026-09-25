package com.chris64233.cc.peerreview.web.dto;

import com.chris64233.cc.peerreview.domain.SubmissionStatus;

import java.util.List;

public record SubmissionAssignmentDetail(Long submissionId, String manuscriptNo,
                                         SubmissionStatus status, int requiredReviewers,
                                         long activeAssignments, List<AssignmentView> assignments) {
}
