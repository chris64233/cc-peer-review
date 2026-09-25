package com.chris64233.cc.peerreview.repository;

import com.chris64233.cc.peerreview.domain.Reviewer;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReviewerRepository extends JpaRepository<Reviewer, Long> {

    boolean existsByCode(String code);

    Optional<Reviewer> findByCode(String code);

    /**
     * 匹配任一擅长主题的启用评审人。冲突过滤、工作量校验与排序在服务层完成。
     */
    @Query("""
            select distinct r
            from Reviewer r
            join r.topics t
            where r.enabled = true and t in :topics
            """)
    List<Reviewer> findEnabledByTopicIn(@Param("topics") Collection<String> topics);

    /**
     * 按 ID 升序加行级写锁并返回评审人，保证并发事务以相同顺序加锁、不会死锁，
     * 且负载判定基于锁内的真实状态，不会突破任何评审人的工作量上限。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select r from Reviewer r where r.id in :ids order by r.id asc")
    List<Reviewer> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);
}
