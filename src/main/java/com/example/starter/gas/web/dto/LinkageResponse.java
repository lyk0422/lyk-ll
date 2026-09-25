package com.example.starter.gas.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 压力调节联动响应：调压与加注调整同一事务下发。
 *
 * @param pressureAction HOLD 合格（含恰等限值）/ RAISE 低于下限升压 / LOWER 高于上限泄压
 * @param regulated      是否发生调压动作
 * @param acceptedAsLate 结算日已封存时仅登记迟到事件
 */
public record LinkageResponse(
        String requestId,
        String stationCode,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime detectedAt,
        BigDecimal pressureKpa,
        BigDecimal lowKpa,
        BigDecimal highKpa,
        String pressureAction,
        Long taskId,
        boolean regulated,
        boolean acceptedAsLate,
        InjectionView injection,
        boolean idempotentReplayed
) {
}
