package com.chris64233.cc.peerreview.repository;

import com.chris64233.cc.peerreview.domain.Submission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
}
