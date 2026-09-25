package com.example.starter.deicing.service;

import com.example.starter.deicing.db.DeicingAllocationPo;
import com.example.starter.deicing.db.DeicingRequestPo;
import com.example.starter.deicing.web.dto.BayTimelineView;
import com.example.starter.deicing.web.dto.QueueItemView;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * PO 到接口视图的转换。
 */
@Component
public class ViewMapper {

    public QueueItemView toView(DeicingRequestPo po) {
        return new QueueItemView(
                po.getRequestId(),
                po.getSeq(),
                po.getFlightNumber(),
                po.getStandCode(),
                po.getType(),
                po.getRequiredBays(),
                po.getDurationMinutes(),
                po.getTargetTakeoffTime(),
                po.getStatus(),
                po.getDeadline(),
                po.getScheduledStart(),
                po.getScheduledEnd(),
                po.getActualStart(),
                po.getHotMinutes(),
                po.getHotRiskReason(),
                po.getEarliestReassignTime(),
                po.getActualTakeoffTime(),
                po.getCreatedAt(),
                po.getUpdatedAt());
    }

    public BayTimelineView.OccupancyView toOccupancy(DeicingAllocationPo po) {
        return new BayTimelineView.OccupancyView(
                po.getRequestId(), po.getFlightNumber(),
                po.getStartTime(), po.getEndTime(), po.getStatus());
    }

    public Instant hotDeadline(DeicingRequestPo po) {
        if (po.getHotMinutes() == null || po.getScheduledEnd() == null) {
            return null;
        }
        return po.getScheduledEnd().plusSeconds(po.getHotMinutes() * 60L);
    }
}
