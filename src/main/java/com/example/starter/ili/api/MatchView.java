package com.example.starter.ili.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 匹配对视图。
 *
 * @param id             匹配对主键
 * @param oldReadingKey  旧回合读数键
 * @param newReadingKey  新回合读数键
 * @param distanceM      里程差绝对值，单位米
 * @param growthRatePct  深度年增长率，单位 %/年
 * @param grade          系统分级
 * @param effectiveGrade 生效分级（降级后取复核分级）
 * @param reviewStatus   复核状态
 * @param reviewer       复核人，未复核为 null
 * @param reviewedAt     复核时间，未复核为 null
 */
public record MatchView(long id, String oldReadingKey, String newReadingKey,
                        BigDecimal distanceM, BigDecimal growthRatePct,
                        String grade, String effectiveGrade, String reviewStatus,
                        String reviewer, LocalDateTime reviewedAt) {
}
