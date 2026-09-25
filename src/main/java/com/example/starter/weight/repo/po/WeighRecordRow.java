package com.example.starter.weight.repo.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 过磅检测记录行。重量单位千克；时间均为 UTC。
 *
 * @param id           主键
 * @param requestId    过磅幂等请求标识
 * @param fingerprint  幂等指纹
 * @param plateNo      车牌号
 * @param stationCode  检测站编码
 * @param weighTime    过磅时刻（UTC）
 * @param mode         称重方式（DYNAMIC/STATIC）
 * @param totalKg      实测总重（千克）
 * @param axleType     轴型编码
 * @param limitKg      判定限值（千克）
 * @param overloadRate 超限率（两位小数）
 * @param tier         分档（NO_OVERLOAD/UNLOAD_RELEASE/FINE_POINTS）
 * @param role         记录角色（AUTHORITATIVE/SUPERSEDED/LATE_EVIDENCE）
 * @param caseId       关联案件主键；迟到数据仍关联已结案件
 * @param createdAt    创建时间（UTC）
 */
public record WeighRecordRow(
        Long id,
        String requestId,
        String fingerprint,
        String plateNo,
        String stationCode,
        Instant weighTime,
        String mode,
        BigDecimal totalKg,
        String axleType,
        BigDecimal limitKg,
        BigDecimal overloadRate,
        String tier,
        String role,
        Long caseId,
        Instant createdAt) {
}
