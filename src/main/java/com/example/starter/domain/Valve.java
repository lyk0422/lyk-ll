package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单元调节阀门，每单元一个，记录当前开度。
 *
 * @param id             主键
 * @param unitId         所属单元 ID，唯一
 * @param openingPercent 当前阀门开度，单位 %，取值 0~100
 * @param updatedAt      开度最后修改时间（UTC）
 */
public record Valve(long id, long unitId, BigDecimal openingPercent, Instant updatedAt) {
}
