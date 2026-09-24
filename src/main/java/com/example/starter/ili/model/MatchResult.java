package com.example.starter.ili.model;

import java.math.BigDecimal;

/**
 * 匹配引擎输出的一条匹配对。
 *
 * @param oldKey        旧回合读数键
 * @param newKey        新回合读数键
 * @param distanceM     里程差绝对值，单位米
 * @param growthRatePct 深度年增长率，单位 %/年
 * @param grade         系统分级
 */
public record MatchResult(String oldKey, String newKey, BigDecimal distanceM,
                          BigDecimal growthRatePct, Grade grade) {
}
