package com.example.starter.gas.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运行监测响应。
 *
 * @param consecutiveViolations 截至本次的连续越限次数（复开清零、合格清零）
 * @param alarmTriggered        本次是否恰好连续 2 次越限而触发告警并锁阀
 * @param acceptedAsLate        结算日已封存时仅登记迟到事件，不落检测数据
 */
public record DetectionResponse(
        String requestId,
        String stationCode,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime detectedAt,
        BigDecimal concentrationMg,
        BigDecimal targetMg,
        BigDecimal lowerMg,
        BigDecimal upperMg,
        boolean inRange,
        int consecutiveViolations,
        boolean alarmTriggered,
        String valveState,
        boolean alarmActive,
        boolean acceptedAsLate,
        InjectionView injection,
        boolean idempotentReplayed
) {
}
