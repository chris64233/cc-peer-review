package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.service.SubmissionService;
import com.chris64233.cc.peerreview.web.dto.SubmissionDto;
import com.chris64233.cc.peerreview.web.dto.SubmissionRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubmissionDto create(@Valid @RequestBody SubmissionRequest request) {
        return submissionService.create(request);
    }

    @GetMapping("/{manuscriptNo}")
    public SubmissionDto get(@PathVariable String manuscriptNo) {
        return submissionService.get(manuscriptNo);
    }

    @GetMapping
    public List<SubmissionDto> list() {
        return submissionService.list();
    }
}
