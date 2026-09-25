package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

/**
 * 分次执行记录结果。
 *
 * @param fractionId        分次台账ID
 * @param patientId         患者ID
 * @param planCode          计划业务编码
 * @param planVersionId     执行时计划版本ID
 * @param versionNo         执行时计划版本号
 * @param fractionNo        分次号
 * @param deliveredDoseCgy  实际投递剂量(cGy)
 * @param requestId         幂等键
 * @param replayed          是否为同键同参重放
 * @param executedAt        执行时间
 */
public record FractionResponse(
        Long fractionId,
        String patientId,
        String planCode,
        Long planVersionId,
        Integer versionNo,
        Integer fractionNo,
        Long deliveredDoseCgy,
        String requestId,
        boolean replayed,
        OffsetDateTime executedAt) {
}
