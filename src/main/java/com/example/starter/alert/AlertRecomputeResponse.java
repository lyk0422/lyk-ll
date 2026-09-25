package com.example.starter.alert;

import java.time.LocalDate;

/**
 * 产地预警复算结果：按快检记录重算当日疑似批次数，与台账比对。
 */
public record AlertRecomputeResponse(
        String origin,
        String variety,
        LocalDate alertDate,
        long suspectedCount,
        int threshold,
        boolean shouldAlert,
        boolean alertExists) {
}
