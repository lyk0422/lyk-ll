package com.example.starter.weight.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 非现场动态称重接入响应。
 *
 * @param recordId       非现场接入记录主键
 * @param plateNo        车牌号
 * @param laneCode       车道编码
 * @param deviceCode     称重设备编码
 * @param weighTime      过磅时刻（UTC）
 * @param totalKg        实测总重（千克）
 * @param overloadRate   超限率（两位小数）
 * @param tier           分档结果
 * @param evidenceStatus 证据状态（COMPLETE/MANUAL_PENDING/DUPLICATE/LATE）
 * @param missingItems   证据链缺项（IMAGE_REF/DEVICE_STATUS/CALIBRATION_EXPIRED）；无缺项为空列表
 * @param caseNo         关联案件编号；未立案为 null
 * @param decisionDocNo  自动生成的处罚决定文书编号；未生成为 null
 * @param lateEvent      是否为结案后迟到事件（只记迟到事件，不改已结案件）
 * @param replayed       是否为幂等重放（同键同参返回首次结果）
 * @param deduped        是否命中“车牌+时刻窗口”去重（窗口内只保留首次）
 */
public record OffsiteWeighResponse(
        Long recordId,
        String plateNo,
        String laneCode,
        String deviceCode,
        Instant weighTime,
        BigDecimal totalKg,
        BigDecimal overloadRate,
        String tier,
        String evidenceStatus,
        List<String> missingItems,
        String caseNo,
        String decisionDocNo,
        boolean lateEvent,
        boolean replayed,
        boolean deduped) {
}
