package com.example.starter.metro.web.dto;

import java.time.LocalDate;

/**
 * 行程响应。金额单位分，里程单位公里。
 */
public record TripResponse(
        long tripId,
        String ticketId,
        String status,
        String entryStation,
        String exitStation,
        Integer distanceKm,
        Integer chainCumKm,
        Long fareFen,
        Long provisionalFareFen,
        LocalDate batchDate,
        boolean overtime,
        boolean transferChained) {
}
