package com.example.starter.blood.web;

import jakarta.validation.constraints.NotNull;

import com.example.starter.blood.domain.TestOutcome;
import com.example.starter.blood.domain.TestType;

/**
 * 检测结果回传请求。
 *
 * @param testType 检测项：INITIAL 初检 / RECHECK 复检
 * @param result   检测结果：QUALIFIED 合格 / UNQUALIFIED 不合格
 */
public record TestResultRequest(@NotNull TestType testType, @NotNull TestOutcome result) {
}
