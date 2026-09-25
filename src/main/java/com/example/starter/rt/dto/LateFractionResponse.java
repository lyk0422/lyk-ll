package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

/**
 * 结案后迟到分次事件登记结果（HTTP 200：事件已留痕，但未计入分次台账、未执行投递）。
 *
 * @param result        固定值 LATE_EVENT_RECORDED
 * @param patientId     患者ID
 * @param planCode      计划业务编码
 * @param planVersionId 指向版本ID
 * @param versionNo     指向版本号
 * @param fractionNo    迟到分次号
 * @param requestId     幂等键
 * @param replayed      是否为同键同参重放
 * @param createdAt      事件登记时间
 */
public record LateFractionResponse(
        String result,
        String patientId,
        String planCode,
        Long planVersionId,
        Integer versionNo,
        Integer fractionNo,
        String requestId,
        boolean replayed,
        OffsetDateTime createdAt) {
}
