package com.chris64233.cc.peerreview.repository;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    /**
     * 评审人当前有效任务数（仅 ACTIVE 占用工作量）。
     */
    @Query("""
            select count(a) from Assignment a
            where a.reviewer.id = :reviewerId and a.status = com.chris64233.cc.peerreview.domain.AssignmentStatus.ACTIVE
            """)
    long countActiveByReviewerId(@Param("reviewerId") Long reviewerId);

    @Query("""
            select a.reviewer.id, count(a) from Assignment a
            where a.status = com.chris64233.cc.peerreview.domain.AssignmentStatus.ACTIVE
            group by a.reviewer.id
            """)
    List<Object[]> countActiveGroupByReviewer();

    /**
     * 某稿件的全部分配记录（含已拒绝），join fetch 便于服务层读取关联信息。
     */
    @Query("""
            select a from Assignment a
            join fetch a.reviewer r
            join fetch a.submission s
            where s.manuscriptNo = :manuscriptNo
            order by case a.status
                when com.chris64233.cc.peerreview.domain.AssignmentStatus.ACTIVE then 0
                else 1 end,
                a.id asc
            """)
    List<Assignment> findBySubmissionManuscriptNo(@Param("manuscriptNo") String manuscriptNo);

    @Query("""
            select a from Assignment a
            join fetch a.reviewer
            where a.submission.manuscriptNo = :manuscriptNo
              and a.reviewer.code = :reviewerCode
            """)
    List<Assignment> findBySubmissionManuscriptNoAndReviewerCode(
            @Param("manuscriptNo") String manuscriptNo,
            @Param("reviewerCode") String reviewerCode);

    /**
     * 该稿件此前是否已分配过该评审人（含已拒绝）。
     */
    @Query("""
            select count(a) > 0 from Assignment a
            where a.submission.id = :submissionId and a.reviewer.id = :reviewerId
            """)
    boolean existsBySubmissionIdAndReviewerId(@Param("submissionId") Long submissionId,
                                              @Param("reviewerId") Long reviewerId);

    @Query("""
            select count(a) from Assignment a
            where a.submission.id = :submissionId and a.status = :status
            """)
    long countBySubmissionIdAndStatus(@Param("submissionId") Long submissionId,
                                      @Param("status") AssignmentStatus status);

    @Query("""
            select count(a) from Assignment a
            where a.submission.id = :submissionId and a.status in :statuses
            """)
    long countBySubmissionIdAndStatusIn(@Param("submissionId") Long submissionId,
                                        @Param("statuses") Collection<AssignmentStatus> statuses);

    /**
     * 对任务行加悲观写锁。提交、延期审批与逾期扫描都先锁稿件再锁任务，
     * 保证同一任务的并发操作串行化，只能产生一个终态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select a from Assignment a where a.id = :id")
    Optional<Assignment> findByIdForUpdate(@Param("id") Long id);

    /**
     * 已到截止期仍未提交的有效任务 ID（按 ID 升序，限量一批）。
     * 只读快照：具体任务是否真正逾期在处理事务内加锁后复检，因此扫描天然幂等、可重入。
     */
    @Query("""
            select a.id from Assignment a
            where a.status = com.chris64233.cc.peerreview.domain.AssignmentStatus.ACTIVE
              and a.dueAt is not null and a.dueAt < :now
            order by a.id asc
            """)
    List<Long> findOverdueIds(@Param("now") Instant now, Pageable pageable);
}
