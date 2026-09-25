package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.service.AssignmentService;
import com.chris64233.cc.peerreview.web.dto.CreateReviewerRequest;
import com.chris64233.cc.peerreview.web.dto.ReviewerLoadView;
import com.chris64233.cc.peerreview.web.dto.ReviewerView;
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
@RequestMapping("/api/reviewers")
public class ReviewerController {

    private final ReviewerRepository reviewerRepository;
    private final AssignmentService assignmentService;

    public ReviewerController(ReviewerRepository reviewerRepository, AssignmentService assignmentService) {
        this.reviewerRepository = reviewerRepository;
        this.assignmentService = assignmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewerView create(@Valid @RequestBody CreateReviewerRequest request) {
        Reviewer reviewer = reviewerRepository.save(new Reviewer(
                request.reviewerNo(), request.name(), request.affiliation(),
                request.topics(), request.maxConcurrent(), request.active()));
        return toView(reviewer);
    }

    @GetMapping("/{id}/load")
    public ReviewerLoadView load(@PathVariable Long id) {
        return assignmentService.getReviewerLoad(id);
    }

    private ReviewerView toView(Reviewer reviewer) {
        return new ReviewerView(reviewer.getId(), reviewer.getReviewerNo(), reviewer.getName(),
                reviewer.getAffiliation(), reviewer.getTopics(), reviewer.getMaxConcurrent(),
                reviewer.isActive());
    }
}
