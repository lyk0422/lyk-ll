package com.example.starter.wb.domain;

import java.time.LocalDateTime;

/**
 * 集装器实物占用行（uld_occupation）：未关闭航班期间编号全局唯一，关闭放行时原子释放。
 */
public record UldOccupationRow(
        String uldNumber,
        long flightId,
        long planId,
        LocalDateTime occupiedAt
) {
}
