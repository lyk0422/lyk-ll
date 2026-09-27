package com.example.starter.wb.domain;

import java.time.LocalDateTime;

/**
 * 集装器实物占用行（uld_occupancy）。
 * 编号在所有未关闭航班内全局唯一；一器一舱位；关闭放行时 open_flag 置 NULL 原子释放，行保留只读。
 */
public record UldOccupancyRow(
        Long id,
        String uldNo,
        String uldType,
        long flightId,
        long manifestId,
        long planId,
        String positionCode,
        int armMm,
        int tareKg,
        int netKg,
        int grossKg,
        LocalDateTime occupiedAt,
        Long openFlag,
        LocalDateTime releasedAt
) {
}
