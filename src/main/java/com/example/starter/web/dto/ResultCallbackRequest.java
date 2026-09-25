package com.example.starter.web.dto;

import com.example.starter.domain.TestResultValue;
import com.example.starter.domain.TestType;

import jakarta.validation.constraints.NotNull;

/**
 * 检测结果回传请求。
 *
 * @param testType 检测项：INITIAL 初检 / RECHECK 复检
 * @param result   结果：PASS 合格 / FAIL 不合格
 */
public record ResultCallbackRequest(
        @NotNull TestType testType,
        @NotNull TestResultValue result) {
}
