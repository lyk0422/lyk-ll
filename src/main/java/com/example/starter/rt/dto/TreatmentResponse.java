package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

/**
 * 受控治疗记录响应：完成即原子释放占用并冻结实绩。
 *
 * @param fractionExecutionId 分次台账ID
 * @param scheduleItemId      排程项ID
 * @param patientId           患者ID
 * @param planCode            计划业务编码
 * @param planVersionId       执行时计划版本ID
 * @param versionNo           执行时计划版本号
 * @param fractionNo          分次号
 * @param deliveredDoseCgy    实绩投递剂量(cGy)
 * @param executedAtUtc       实绩执行时间（UTC）
 * @param requestId           幂等键
 * @param replayed            是否幂等重放
 */
public record TreatmentResponse(
        long fractionExecutionId,
        long scheduleItemId,
        String patientId,
        String planCode,
        long planVersionId,
        int versionNo,
        int fractionNo,
        long deliveredDoseCgy,
        OffsetDateTime executedAtUtc,
        String requestId,
        boolean replayed) {
}
