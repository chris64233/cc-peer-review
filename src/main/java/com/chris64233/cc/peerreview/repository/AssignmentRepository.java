package com.chris64233.cc.peerreview.repository;

import com.chris64233.cc.peerreview.domain.Assignment;
import com.chris64233.cc.peerreview.domain.AssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

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
}
