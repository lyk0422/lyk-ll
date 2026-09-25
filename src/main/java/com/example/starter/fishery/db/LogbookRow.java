package com.example.starter.fishery.db;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 渔捞日志行；按作业时刻（UTC）归属日界，按“船＋日期＋渔区”唯一去重。
 *
 * @param logDate 作业时刻按 UTC 归属的日期
 * @param seqNo 同一航次内按作业时刻排序的日志链序号（乱序提交也归位）
 * @param bycatchFlag 本日志兼捕保护鱼种超过允许比例 5%，须单独标注
 * @param late 结案后迟到日志标志（只记迟到事件并入审查队列，不参与核销）
 */
public record LogbookRow(
        Long id,
        String tripCode,
        String vesselCode,
        String zoneCode,
        LocalDate logDate,
        Instant fishingAt,
        BigDecimal totalKg,
        boolean bycatchFlag,
        boolean late,
        int seqNo,
        Instant createdAt) {
}
