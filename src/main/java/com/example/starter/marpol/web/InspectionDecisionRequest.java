package com.example.starter.marpol.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 核查官逐条结论：CONFIRM-确认；VIOLATION-标注违规（立即生成缺陷项）。
 */
public record InspectionDecisionRequest(
        @NotBlank String recordNo,
        @NotBlank String conclusion,
        String evidence) {
}
