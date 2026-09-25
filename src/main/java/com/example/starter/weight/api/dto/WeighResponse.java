package com.example.starter.weight.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 过磅上报/查询响应。
 *
 * @param recordId     检测记录主键
 * @param plateNo      车牌号
 * @param stationCode  检测站编码
 * @param weighTime    过磅时刻（UTC）
 * @param mode         称重方式
 * @param totalKg      实测总重（千克）
 * @param limitKg      判定限值（千克）
 * @param overloadRate 超限率（两位小数）
 * @param tier         分档结果
 * @param role         记录角色（AUTHORITATIVE/SUPERSEDED/LATE_EVIDENCE）
 * @param caseNo       关联案件编号；未立案为 null
 * @param lateEvent    是否为结案后迟到事件
 * @param replayed     是否为幂等重放（同键同参返回首次结果）
 * @param deduped      是否命中“车牌+检测站+过磅时刻+方式”去重而未新建记录
 */
public record WeighResponse(
        Long recordId,
        String plateNo,
        String stationCode,
        Instant weighTime,
        String mode,
        BigDecimal totalKg,
        BigDecimal limitKg,
        BigDecimal overloadRate,
        String tier,
        String role,
        String caseNo,
        boolean lateEvent,
        boolean replayed,
        boolean deduped) {
}
