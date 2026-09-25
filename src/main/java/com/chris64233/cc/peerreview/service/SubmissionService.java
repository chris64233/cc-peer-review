package com.chris64233.cc.peerreview.service;

import com.chris64233.cc.peerreview.domain.Author;
import com.chris64233.cc.peerreview.domain.Submission;
import com.chris64233.cc.peerreview.exception.ConflictException;
import com.chris64233.cc.peerreview.exception.ResourceNotFoundException;
import com.chris64233.cc.peerreview.repository.SubmissionRepository;
import com.chris64233.cc.peerreview.web.dto.AuthorRequest;
import com.chris64233.cc.peerreview.web.dto.SubmissionDto;
import com.chris64233.cc.peerreview.web.dto.SubmissionRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class SubmissionService {

    private final SubmissionRepository submissionRepository;

    public SubmissionService(SubmissionRepository submissionRepository) {
        this.submissionRepository = submissionRepository;
    }

    @Transactional
    public SubmissionDto create(SubmissionRequest request) {
        if (submissionRepository.existsByManuscriptNo(request.manuscriptNo())) {
            throw new ConflictException("稿件号已存在: " + request.manuscriptNo());
        }
        Set<Author> authors = request.authors().stream()
                .map(author -> new Author(author.code(), author.affiliation()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Submission submission = new Submission(
                request.manuscriptNo(),
                request.topics(),
                authors,
                request.requiredReviewers());
        submissionRepository.saveAndFlush(submission);
        return toDto(submission);
    }

    @Transactional(readOnly = true)
    public SubmissionDto get(String manuscriptNo) {
        return toDto(findByManuscriptNo(manuscriptNo));
    }

    @Transactional(readOnly = true)
    public List<SubmissionDto> list() {
        return submissionRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(this::toDto)
                .toList();
    }

    Submission findByManuscriptNo(String manuscriptNo) {
        return submissionRepository.findByManuscriptNo(manuscriptNo)
                .orElseThrow(() -> new ResourceNotFoundException("投稿不存在: " + manuscriptNo));
    }

    private SubmissionDto toDto(Submission submission) {
        Set<AuthorRequest> authors = submission.getAuthors().stream()
                .map(author -> new AuthorRequest(author.getCode(), author.getAffiliation()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new SubmissionDto(
                submission.getId(),
                submission.getManuscriptNo(),
                Set.copyOf(submission.getTopics()),
                authors,
                submission.getRequiredReviewers(),
                submission.getStatus().name());
    }
}
