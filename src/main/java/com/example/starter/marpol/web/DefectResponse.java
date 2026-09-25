package com.example.starter.marpol.web;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 缺陷项响应视图。
 */
public record DefectResponse(
        Long id,
        String inspectionNo,
        String recordNo,
        Integer recordVersion,
        LocalDate deadline,
        String status,
        Instant closedAt,
        Instant createdAt) {
}
