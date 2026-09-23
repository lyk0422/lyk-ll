package com.example.starter.dr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * 写入/更正单个曲线点请求。
 * 同一电表、区间、数据类型首次写入时 expectedVersion 为空（或为 0 语义等价：记录不得存在）；
 * 更正时必须显式携带当前版本号（前版本号）。
 *
 * @param dataType        数据类型：BASELINE=基线，ACTUAL=实测
 * @param intervalIdx     事件区间编号，从 0 开始
 * @param value           有限非负小数功率值（kW）
 * @param expectedVersion 期望的当前版本号；首次写入传 null，更正传前版本号
 */
public record PointWriteRequest(
        @NotBlank String dataType,
        @NotNull Integer intervalIdx,
        @NotNull @PositiveOrZero BigDecimal value,
        Integer expectedVersion) {
}
