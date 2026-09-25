package com.example.starter.cutover.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 步骤逐条回报请求。requestUid 为回报幂等键。
 *
 * @param success    本步骤是否通过：硬性检查/关键验证点传 false 即命中回退判据
 * @param reportedAt 回报时间（Asia/Shanghai）；关键验证点据此与开始时间判定 10 分钟超时
 * @param remark     备注，可空
 */
public record StepReportRequest(
        @NotBlank String requestUid,
        @NotBlank String requestId,
        @NotNull Integer seqNo,
        @NotNull Boolean success,
        @NotNull LocalDateTime reportedAt,
        String remark
) {
}
