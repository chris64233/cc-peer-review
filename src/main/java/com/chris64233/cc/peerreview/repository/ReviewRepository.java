package com.chris64233.cc.peerreview.repository;

import com.chris64233.cc.peerreview.domain.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByAssignmentId(Long assignmentId);
}
