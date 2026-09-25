package com.example.starter.deicing.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 单个车位的占用时间线（半开区间 [start, end)）。
 */
public record BayTimelineView(String bayCode, List<OccupancyView> occupancies) {

    /**
     * 车位占用区间。
     */
    public record OccupancyView(
            String requestId,
            String flightNumber,
            Instant start,
            Instant end,
            String status) {
    }
}
