package com.example.starter.runway.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 摩擦系数轨迹点：按评估时间排序可复算。
 *
 * @param assessmentId       评估记录ID
 * @param frictionCoefficient 摩擦系数（恰0.35合格）
 * @param assessmentType     评估类型
 * @param passed             是否合格
 * @param retest             是否修复后复测
 * @param assessedAt         评估时间（UTC）
 */
public record FrictionPoint(
        long assessmentId,
        BigDecimal frictionCoefficient,
        String assessmentType,
        boolean passed,
        boolean retest,
        Instant assessedAt) {
}
