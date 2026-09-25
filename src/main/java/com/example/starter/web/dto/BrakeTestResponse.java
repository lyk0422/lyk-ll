package com.example.starter.web.dto;

/**
 * 制动试验响应。
 *
 * @param testId         试验记录ID
 * @param testType       试验类型
 * @param result         结论：PASS/FAIL
 * @param consistVersion 试验对应编组版本
 * @param message        说明
 */
public record BrakeTestResponse(
        long testId,
        String testType,
        String result,
        int consistVersion,
        String message) {
}
