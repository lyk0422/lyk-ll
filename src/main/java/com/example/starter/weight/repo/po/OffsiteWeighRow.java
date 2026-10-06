package com.example.starter.weight.repo.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 非现场动态称重接入记录行。重量单位千克；时间均为 UTC。
 *
 * @param id          主键
 * @param requestId   幂等请求标识
 * @param fingerprint 幂等指纹（requestId 与全部业务字段）
 * @param plateNo     车牌号
 * @param laneNo      车道号
 * @param weighTime   过磅时刻（UTC）
 * @param totalKg     实测总重（千克）
 * @param imageRef    合成影像标识；缺失为 null（证据链缺项）
 * @param deviceId    称重设备编号
 * @param status      接入状态（AUTO_DECIDED/MANUAL_PENDING/LATE_EVENT）
 * @param caseId      关联案件主键；人工待补未立案为 null
 * @param decisionId  自动生成的处罚决定主键；未生成为 null
 * @param createdAt   接入时间（UTC）
 */
public record OffsiteWeighRow(
        Long id,
        String requestId,
        String fingerprint,
        String plateNo,
        String laneNo,
        Instant weighTime,
        BigDecimal totalKg,
        String imageRef,
        String deviceId,
        String status,
        Long caseId,
        Long decisionId,
        Instant createdAt) {
}
