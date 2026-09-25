package com.example.starter.gas.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 浓度曲线复算单点：依据落库检测值按目标浓度 ±20%（含边界）重新判定。
 *
 * @param lockPoint 重放计数后该点是否为恰连续 2 次越限的锁阀点
 */
public record ConCurvePoint(
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime detectedAt,
        BigDecimal concentrationMg,
        boolean inRange,
        int runningViolations,
        boolean lockPoint
) {
}
