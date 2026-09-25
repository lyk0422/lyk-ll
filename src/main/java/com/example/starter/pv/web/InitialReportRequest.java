package com.example.starter.pv.web;

import java.time.Instant;
import java.time.LocalDate;

import com.example.starter.pv.domain.Seriousness;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 初报请求；所有日期按 UTC 日界解释。
 *
 * @param requestId 客户端幂等键
 * @param patientCode 合成患者编码
 * @param patientName 合成患者姓名
 * @param drugCode 合成药品编码
 * @param drugName 合成药品名称
 * @param reactionEvent 不良反应事件标准名称
 * @param seriousness 严重程度
 * @param awarenessDate 获知日 T0（UTC）
 * @param submittedAt 实际提交时刻（UTC），用于时限判定
 * @param lateReason 迟报原因；迟报时强制非空，及时时必须为空
 */
public record InitialReportRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String patientCode,
        @NotBlank @Size(max = 128) String patientName,
        @NotBlank @Size(max = 64) String drugCode,
        @NotBlank @Size(max = 255) String drugName,
        @NotBlank @Size(max = 255) String reactionEvent,
        @NotNull Seriousness seriousness,
        @NotNull LocalDate awarenessDate,
        @NotNull Instant submittedAt,
        @Size(max = 500) String lateReason) {
}
