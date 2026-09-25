package com.example.starter.survey.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 船舶行记录（合成数据）。
 */
public record ShipRow(
        Long id,
        String imo,
        String name,
        LocalDate anniversaryDate,
        LocalDateTime createdAt) {
}
