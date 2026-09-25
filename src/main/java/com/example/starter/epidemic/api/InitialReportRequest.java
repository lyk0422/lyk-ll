package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 初报请求。诊断时刻使用带偏移量的 ISO-8601 时间，内部按 UTC 起算时限。
 */
public record InitialReportRequest(
        @NotBlank(message = "requestId 不能为空")
        @Size(max = 80)
        String requestId,
        @NotBlank(message = "patientId 不能为空")
        String patientId,
        String patientName,
        @NotBlank(message = "diseaseCode 不能为空")
        String diseaseCode,
        @NotBlank(message = "diagnosisType 不能为空：SUSPECTED/CONFIRMED")
        String diagnosisType,
        @NotNull(message = "onsetDate 不能为空，格式 yyyy-MM-dd")
        LocalDate onsetDate,
        @NotNull(message = "diagnosisTime 不能为空，ISO-8601 带偏移量")
        OffsetDateTime diagnosisTime,
        @NotBlank(message = "facilityCode 不能为空")
        String facilityCode,
        @NotBlank(message = "reporter 不能为空")
        String reporter,
        /** 迟报时强制填写的迟报原因；及时时应为空。 */
        String lateReason,
        String notes
) {
}
