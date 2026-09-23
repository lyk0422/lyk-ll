package com.example.starter.dr.dto;

import java.math.BigDecimal;

/**
 * 曲线点写入/更正结果。
 *
 * @param meterId     电表业务编码
 * @param dataType    数据类型：BASELINE/ACTUAL
 * @param intervalIdx 区间编号
 * @param value       当前数值（kW）
 * @param version     写入后的新版本号
 * @param corrected   true=更正既有版本，false=首次写入
 */
public record PointResponse(
        String meterId,
        String dataType,
        int intervalIdx,
        BigDecimal value,
        int version,
        boolean corrected) {
}
