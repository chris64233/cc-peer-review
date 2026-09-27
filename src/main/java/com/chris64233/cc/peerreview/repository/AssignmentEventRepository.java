package com.chris64233.cc.peerreview.repository;

import com.chris64233.cc.peerreview.domain.AssignmentEvent;
import com.chris64233.cc.peerreview.domain.AssignmentEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AssignmentEventRepository extends JpaRepository<AssignmentEvent, Long> {

    /** 某稿件的全部事件，按写入顺序排列，用于展示截止期变化与历次改派。 */
    List<AssignmentEvent> findByManuscriptNoOrderByIdAsc(String manuscriptNo);

    /** 某任务的延期类事件，用于判断是否存在待审批申请、是否已批准过延期。 */
    List<AssignmentEvent> findByAssignmentIdAndTypeInOrderByIdAsc(Long assignmentId,
                                                                  Collection<AssignmentEventType> types);
}
