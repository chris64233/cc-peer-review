package com.chris64233.cc.peerreview.repository;

import com.chris64233.cc.peerreview.domain.Submission;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    boolean existsByManuscriptNo(String manuscriptNo);

    Optional<Submission> findByManuscriptNo(String manuscriptNo);

    /**
     * 对稿件行加悲观写锁，串行化同一稿件的自动分配与拒绝/替补操作。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select s from Submission s where s.manuscriptNo = :manuscriptNo")
    Optional<Submission> findByManuscriptNoForUpdate(@Param("manuscriptNo") String manuscriptNo);
}
