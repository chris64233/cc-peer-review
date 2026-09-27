package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.service.ReviewService;
import com.chris64233.cc.peerreview.web.dto.ReviewDto;
import com.chris64233.cc.peerreview.web.dto.ReviewSubmitBody;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/submissions/{manuscriptNo}/assignments/{reviewerCode}/review")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 提交评审意见。任务仍有效时原子转为已完成；已逾期改派的任务仅存档为迟到意见。
     */
    @PostMapping
    public ReviewDto submit(@PathVariable String manuscriptNo,
                            @PathVariable String reviewerCode,
                            @Valid @RequestBody ReviewSubmitBody body) {
        return reviewService.submit(manuscriptNo, reviewerCode, body.content());
    }

    @GetMapping
    public ResponseEntity<ReviewDto> get(@PathVariable String manuscriptNo,
                                         @PathVariable String reviewerCode) {
        return reviewService.find(manuscriptNo, reviewerCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
