package com.example.starter.hazmat.web;

import com.example.starter.hazmat.domain.WaypointStatus;
import com.example.starter.hazmat.model.WaybillWaypoint;
import java.time.LocalDateTime;

/**
 * 途经点视图。
 */
public record WaypointView(
        int seq,
        LocalDateTime plannedPassTime,
        LocalDateTime checkinTime,
        WaypointStatus status) {

    public static WaypointView of(WaybillWaypoint wp) {
        return new WaypointView(wp.seq(), wp.plannedPassTime(), wp.checkinTime(), wp.status());
    }
}
