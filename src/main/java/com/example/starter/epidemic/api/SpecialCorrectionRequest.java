package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 特殊订正发起请求（仅受理已归档卡片）。须两名不同审核员先后确认后才生效。
 */
public record SpecialCorrectionRequest(
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
        /** 迟报时强制填写。 */
        String lateReason,
        @NotBlank(message = "notes 不能为空，须说明疫情核实情况")
        String notes
) {
}
