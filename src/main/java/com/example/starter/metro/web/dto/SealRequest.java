package com.example.starter.metro.web.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 日结封账请求。batchDate 格式 yyyy-MM-dd。
 */
public record SealRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "batchDate 不能为空") LocalDate batchDate) {
}
