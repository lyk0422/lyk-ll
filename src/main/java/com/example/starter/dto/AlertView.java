package com.example.starter.dto;

import java.time.Instant;

/**
 * 告警链条目视图。
 */
public record AlertView(
        int cycleNo,
        String type,
        Instant firedAt,
        Integer farmActualKw,
        Integer reallocatedCycleNo) {
}
