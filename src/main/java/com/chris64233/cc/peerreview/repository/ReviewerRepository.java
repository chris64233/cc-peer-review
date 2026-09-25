package com.chris64233.cc.peerreview.repository;

import com.chris64233.cc.peerreview.domain.Reviewer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ReviewerRepository extends JpaRepository<Reviewer, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reviewer r where r.active = true order by r.id")
    List<Reviewer> lockAllActive();
}
