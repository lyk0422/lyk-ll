package com.example.starter.visit;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 访视数据录入请求。visitTime 为访视发生时刻（UTC，ISO-8601）。
 */
public record VisitRequest(
        @NotBlank(message = "screeningNo 不能为空") String screeningNo,
        @NotNull(message = "visitTime 不能为空") Instant visitTime,
        String note) {
}
