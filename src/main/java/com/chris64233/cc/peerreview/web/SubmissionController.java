package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.domain.Author;
import com.chris64233.cc.peerreview.domain.Submission;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import com.chris64233.cc.peerreview.service.AssignmentService;
import com.chris64233.cc.peerreview.web.dto.CreateSubmissionRequest;
import com.chris64233.cc.peerreview.web.dto.SubmissionAssignmentDetail;
import com.chris64233.cc.peerreview.web.dto.SubmissionView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    private final SubmissionRepository submissionRepository;
    private final AssignmentService assignmentService;

    public SubmissionController(SubmissionRepository submissionRepository,
                                AssignmentService assignmentService) {
        this.submissionRepository = submissionRepository;
        this.assignmentService = assignmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubmissionView create(@Valid @RequestBody CreateSubmissionRequest request) {
        Submission submission = submissionRepository.save(new Submission(
                request.manuscriptNo(), request.topics(),
                request.authors().stream()
                        .map(a -> new Author(a.name(), a.affiliation()))
                        .toList(),
                request.requiredReviewers()));
        return toView(submission);
    }

    @PostMapping("/{id}/assign")
    public SubmissionAssignmentDetail assign(@PathVariable Long id) {
        return assignmentService.assign(id);
    }

    @PostMapping("/{id}/assignments/{reviewerId}/decline")
    public SubmissionAssignmentDetail decline(@PathVariable Long id, @PathVariable Long reviewerId) {
        return assignmentService.decline(id, reviewerId);
    }

    @GetMapping("/{id}/assignments")
    public SubmissionAssignmentDetail assignments(@PathVariable Long id) {
        return assignmentService.getSubmissionDetail(id);
    }

    private SubmissionView toView(Submission submission) {
        return new SubmissionView(submission.getId(), submission.getManuscriptNo(),
                submission.getTopics(),
                submission.getAuthors().stream()
                        .map(a -> new SubmissionView.AuthorView(a.getName(), a.getAffiliation()))
                        .toList(),
                submission.getRequiredReviewers(), submission.getStatus());
    }
}
