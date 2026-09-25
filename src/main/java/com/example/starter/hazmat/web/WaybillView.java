package com.example.starter.hazmat.web;

import com.example.starter.hazmat.domain.WaybillStatus;
import com.example.starter.hazmat.model.Waybill;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 运单视图。
 *
 * @param waypoints 途经点视图列表（按序号排序）
 */
public record WaybillView(
        long id,
        String requestId,
        String vehicleCode,
        int hazmatLevel,
        long routeId,
        WaybillStatus status,
        LocalDateTime plannedDeparture,
        int routeGeneration,
        LocalDateTime closedAt,
        List<WaypointView> waypoints) {

    public static WaybillView of(Waybill w, List<WaypointView> waypoints) {
        return new WaybillView(w.id(), w.requestId(), w.vehicleCode(), w.hazmatLevel(),
                w.routeId(), w.status(), w.plannedDeparture(), w.routeGeneration(),
                w.closedAt(), waypoints);
    }
}
