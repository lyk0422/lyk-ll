package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 普通订正请求（仅审核通过且未归档的卡片受理）。
 */
public record CorrectionRequest(
        @NotBlank(message = "requestId 不能为空")
        @Size(max = 80)
        String requestId,
        @NotBlank(message = "cardNumber 不能为空")
        String cardNumber,
        @NotBlank(message = "diseaseCode 不能为空")
        String diseaseCode,
        @NotBlank(message = "diagnosisType 不能为空：SUSPECTED/CONFIRMED")
        String diagnosisType,
        @NotNull(message = "onsetDate 不能为空")
        LocalDate onsetDate,
        @NotNull(message = "diagnosisTime 不能为空，ISO-8601 带偏移量")
        OffsetDateTime diagnosisTime,
        @NotBlank(message = "reporter 不能为空")
        String reporter,
        /** 迟报时强制填写。 */
        String lateReason,
        String notes
) {
}
