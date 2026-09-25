package com.example.starter.marpol.web;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * 发起港口国核查任务（按船舶）。
 *
 * @param inspectDate 核查日（UTC）；为空取当前 UTC 日期
 * @param reviewOf    复查时引用的原核查号；普通核查留空
 */
public record InspectionCreateRequest(
        @NotBlank String shipId,
        @NotBlank String portCode,
        LocalDate inspectDate,
        String reviewOf) {
}
