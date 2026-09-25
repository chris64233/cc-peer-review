package com.chris64233.cc.peerreview.repository;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    Optional<Assignment> findBySubmissionIdAndReviewerId(Long submissionId, Long reviewerId);

    List<Assignment> findBySubmissionIdOrderById(Long submissionId);

    List<Assignment> findByReviewerIdAndStatusOrderById(Long reviewerId, AssignmentStatus status);

    long countByReviewerIdAndStatus(Long reviewerId, AssignmentStatus status);

    @Query("select a.reviewer.id from Assignment a where a.submission.id = :submissionId")
    Set<Long> findReviewerIdsBySubmissionId(@Param("submissionId") Long submissionId);
}
