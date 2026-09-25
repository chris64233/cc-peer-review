package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.service.ReviewerService;
import com.chris64233.cc.peerreview.web.dto.ReviewerDto;
import com.chris64233.cc.peerreview.web.dto.ReviewerLoadDto;
import com.chris64233.cc.peerreview.web.dto.ReviewerRequest;
import com.chris64233.cc.peerreview.web.dto.ReviewerUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reviewers")
public class ReviewerController {

    private final ReviewerService reviewerService;

    public ReviewerController(ReviewerService reviewerService) {
        this.reviewerService = reviewerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewerDto create(@Valid @RequestBody ReviewerRequest request) {
        return reviewerService.create(request);
    }

    @PatchMapping("/{code}")
    public ReviewerDto update(@PathVariable String code,
                              @Valid @RequestBody ReviewerUpdateRequest request) {
        return reviewerService.update(code, request);
    }

    @GetMapping("/{code}")
    public ReviewerDto get(@PathVariable String code) {
        return reviewerService.get(code);
    }

    @GetMapping
    public List<ReviewerDto> list() {
        return reviewerService.list();
    }

    /**
     * 评审人当前负载：有效任务数与剩余容量。
     */
    @GetMapping("/loads")
    public List<ReviewerLoadDto> loads() {
        return reviewerService.loads();
    }
}
