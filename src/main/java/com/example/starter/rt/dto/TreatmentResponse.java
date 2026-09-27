package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

/**
 * 排程受控治疗记录结果：完成原子释放占用并冻结实绩。
 *
 * @param itemId           排程项ID
 * @param scheduleId       排程批次ID
 * @param patientId        患者ID
 * @param planCode         计划业务编码
 * @param planVersionId    执行时计划版本ID（与排程冻结版本一致）
 * @param versionNo        执行时计划版本号
 * @param fractionNo       分次号
 * @param deliveredDoseCgy 实际投递剂量(cGy)
 * @param requestId        治疗记录幂等键
 * @param replayed         是否为同键同参重放
 * @param executedAtUtc    实绩执行时间（UTC）
 */
public record TreatmentResponse(
        Long itemId,
        Long scheduleId,
        String patientId,
        String planCode,
        Long planVersionId,
        Integer versionNo,
        Integer fractionNo,
        Long deliveredDoseCgy,
        String requestId,
        boolean replayed,
        OffsetDateTime executedAtUtc) {
}
