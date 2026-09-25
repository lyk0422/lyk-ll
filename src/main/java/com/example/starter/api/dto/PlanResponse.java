package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 方案视图。
 *
 * @param id                 方案 ID
 * @param kilnId             窑口编码
 * @param licenseNo          绑定的危废经营许可证编号
 * @param status             方案状态
 * @param declaredHeatRatio  申报热量替代率
 * @param currentHeatRatio   当前执行替代率（降掺后变化）
 * @param floorHeatRatio     保底替代率
 * @param chlorineKgH        加权入窑氯总量，单位 kg/h
 * @param sulfurKgH          加权入窑硫总量，单位 kg/h
 * @param rejectReason       整单拒绝原因
 * @param declaredAt         申报时刻
 * @param executedAt         下发执行时刻
 * @param sealedAt           日结封存时刻
 */
public record PlanResponse(
        Long id,
        String kilnId,
        String licenseNo,
        String status,
        BigDecimal declaredHeatRatio,
        BigDecimal currentHeatRatio,
        BigDecimal floorHeatRatio,
        BigDecimal chlorineKgH,
        BigDecimal sulfurKgH,
        String rejectReason,
        LocalDateTime declaredAt,
        LocalDateTime executedAt,
        LocalDateTime sealedAt,
        LocalDate banDate) {
}
