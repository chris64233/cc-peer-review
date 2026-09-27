package com.chris64233.cc.peerreview.repository;

import com.chris64233.cc.peerreview.domain.ExtensionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ExtensionEventRepository extends JpaRepository<ExtensionEvent, Long> {

    /**
     * 某任务的延期事件时间线（申请/批准/拒绝），按写入顺序返回。
     */
    List<ExtensionEvent> findByAssignmentIdOrderByIdAsc(Long assignmentId);

    @Query("""
            select e from ExtensionEvent e
            where e.assignment.id in :assignmentIds
            order by e.id asc
            """)
    List<ExtensionEvent> findByAssignmentIdInOrderByIdAsc(
            @Param("assignmentIds") Collection<Long> assignmentIds);
}
