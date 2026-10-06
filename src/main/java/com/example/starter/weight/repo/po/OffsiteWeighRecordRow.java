package com.example.starter.weight.repo.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 非现场动态称重接入记录行。重量单位千克；时间均为 UTC。
 *
 * @param id             主键
 * @param requestId      幂等请求标识
 * @param fingerprint    幂等指纹
 * @param plateNo        车牌号
 * @param laneCode       车道编码
 * @param deviceCode     称重设备编码
 * @param weighTime      过磅时刻（UTC）
 * @param totalKg        实测总重（千克）
 * @param axleType       轴型编码
 * @param limitKg        判定限值（千克）
 * @param overloadRate   超限率（两位小数）
 * @param tier           分档
 * @param imageRef       合成影像标识；缺失为 NULL
 * @param evidenceStatus 证据状态（COMPLETE/MANUAL_PENDING/DUPLICATE/LATE）
 * @param missingItems   证据链缺项（逗号分隔）；无缺项为空串
 * @param dedupRefType   窗口去重保留的首次记录类型（ONSITE/OFFSITE）；非去重为 NULL
 * @param dedupRefId     窗口去重保留的首次记录主键；非去重为 NULL
 * @param caseId         关联案件主键；未立案为 NULL
 * @param decisionId     自动生成的处罚决定主键；未生成为 NULL
 * @param createdAt      创建时间（UTC）
 */
public record OffsiteWeighRecordRow(
        Long id,
        String requestId,
        String fingerprint,
        String plateNo,
        String laneCode,
        String deviceCode,
        Instant weighTime,
        BigDecimal totalKg,
        String axleType,
        BigDecimal limitKg,
        BigDecimal overloadRate,
        String tier,
        String imageRef,
        String evidenceStatus,
        String missingItems,
        String dedupRefType,
        Long dedupRefId,
        Long caseId,
        Long decisionId,
        Instant createdAt) {
}
