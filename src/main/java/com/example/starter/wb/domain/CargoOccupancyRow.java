package com.example.starter.wb.domain;

import java.time.LocalDateTime;

/**
 * 集装器实物占用行（cargo_uld_occupancy）。
 * activeFlag=1L 表示占用中，{@code null} 表示关闭放行后已释放；
 * (uld_no, active_flag) 唯一约束保证同器跨未关闭航班最多一方占用。
 */
public record CargoOccupancyRow(
        Long id,
        String uldNo,
        long flightId,
        long manifestId,
        long planId,
        Long activeFlag,
        LocalDateTime occupiedAt,
        LocalDateTime releasedAt
) {
}
