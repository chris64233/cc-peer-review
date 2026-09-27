package com.chris64233.cc.peerreview.repository;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
     * 某稿件的全部分配记录（含已拒绝/已逾期），join fetch 便于服务层读取关联信息。
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
     * 该稿件此前是否已分配过该评审人（含已拒绝、已逾期）。
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

    /**
     * 对单条任务加行级写锁，串行化延期审批与逾期扫描对同一任务的并发判定。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select a from Assignment a where a.id = :id")
    Optional<Assignment> findByIdForUpdate(@Param("id") Long id);

    /**
     * 终态 CAS：仅当任务仍为 ACTIVE 时转为 COMPLETED。
     * 返回 0 表示已有其他并发事务（逾期扫描等）先完成终态转换，本次提交按迟到处理。
     */
    @Modifying
    @Query("""
            update Assignment a
            set a.status = com.chris64233.cc.peerreview.domain.AssignmentStatus.COMPLETED,
                a.statusReason = null,
                a.updatedAt = :when,
                a.version = a.version + 1
            where a.id = :id
              and a.status = com.chris64233.cc.peerreview.domain.AssignmentStatus.ACTIVE
            """)
    int markCompletedIfActive(@Param("id") Long id, @Param("when") Instant when);

    /**
     * 终态 CAS：仅当任务仍为 ACTIVE 且截止期确已到达时转为 OVERDUE。
     * 带上 dueAt 条件可挡住“扫描已启动但延期审批先提交”的竞态：审批顺延截止期后本更新匹配 0 行。
     */
    @Modifying
    @Query("""
            update Assignment a
            set a.status = com.chris64233.cc.peerreview.domain.AssignmentStatus.OVERDUE,
                a.statusReason = :reason,
                a.updatedAt = :when,
                a.version = a.version + 1
            where a.id = :id
              and a.status = com.chris64233.cc.peerreview.domain.AssignmentStatus.ACTIVE
              and a.dueAt is not null and a.dueAt < :when
            """)
    int markOverdueIfActive(@Param("id") Long id, @Param("reason") String reason,
                            @Param("when") Instant when);

    /**
     * 逾期扫描候选：仍为 ACTIVE 且截止期已到的任务 ID，按 ID 升序保证批次处理顺序稳定。
     */
    @Query("""
            select a.id from Assignment a
            where a.status = com.chris64233.cc.peerreview.domain.AssignmentStatus.ACTIVE
              and a.dueAt is not null and a.dueAt < :now
            order by a.id asc
            """)
    List<Long> findDueActiveIds(@Param("now") Instant now);
}
