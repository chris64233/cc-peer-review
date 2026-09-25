package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Reviewer;
import com.chris64233.cc.peerreview.exception.ConflictException;
import com.chris64233.cc.peerreview.exception.ResourceNotFoundException;
import com.chris64233.cc.peerreview.repository.AssignmentRepository;
import com.chris64233.cc.peerreview.repository.ReviewerRepository;
import com.chris64233.cc.peerreview.web.dto.ReviewerDto;
import com.chris64233.cc.peerreview.web.dto.ReviewerLoadDto;
import com.chris64233.cc.peerreview.web.dto.ReviewerRequest;
import com.chris64233.cc.peerreview.web.dto.ReviewerUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ReviewerService {

    private final ReviewerRepository reviewerRepository;
    private final AssignmentRepository assignmentRepository;

    public ReviewerService(ReviewerRepository reviewerRepository,
                           AssignmentRepository assignmentRepository) {
        this.reviewerRepository = reviewerRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Transactional
    public ReviewerDto create(ReviewerRequest request) {
        if (reviewerRepository.existsByCode(request.code())) {
            throw new ConflictException("评审人编号已存在: " + request.code());
        }
        Reviewer reviewer = new Reviewer(
                request.code(),
                request.affiliation(),
                request.topics(),
                request.maxAssignments(),
                request.effectiveEnabled());
        reviewerRepository.saveAndFlush(reviewer);
        return toDto(reviewer);
    }

    @Transactional
    public ReviewerDto update(String code, ReviewerUpdateRequest request) {
        Reviewer reviewer = reviewerRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("评审人不存在: " + code));
        if (request.affiliation() != null) {
            reviewer.setAffiliation(request.affiliation());
        }
        if (request.topics() != null) {
            reviewer.setTopics(request.topics());
        }
        if (request.maxAssignments() != null) {
            reviewer.setMaxAssignments(request.maxAssignments());
        }
        if (request.enabled() != null) {
            reviewer.setEnabled(request.enabled());
        }
        reviewerRepository.saveAndFlush(reviewer);
        return toDto(reviewer);
    }

    @Transactional(readOnly = true)
    public ReviewerDto get(String code) {
        return toDto(findByCode(code));
    }

    @Transactional(readOnly = true)
    public List<ReviewerDto> list() {
        return reviewerRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReviewerLoadDto> loads() {
        java.util.Map<Long, Long> activeCounts = new java.util.HashMap<>();
        for (Object[] row : assignmentRepository.countActiveGroupByReviewer()) {
            activeCounts.put((Long) row[0], (Long) row[1]);
        }
        return reviewerRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(reviewer -> {
                    long active = activeCounts.getOrDefault(reviewer.getId(), 0L);
                    return new ReviewerLoadDto(
                            reviewer.getId(),
                            reviewer.getCode(),
                            reviewer.getAffiliation(),
                            reviewer.getMaxAssignments(),
                            active,
                            reviewer.getMaxAssignments() - active);
                })
                .toList();
    }

    private Reviewer findByCode(String code) {
        return reviewerRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("评审人不存在: " + code));
    }

    private ReviewerDto toDto(Reviewer reviewer) {
        return new ReviewerDto(
                reviewer.getId(),
                reviewer.getCode(),
                reviewer.getAffiliation(),
                java.util.Set.copyOf(reviewer.getTopics()),
                reviewer.getMaxAssignments(),
                reviewer.isEnabled());
    }
}
