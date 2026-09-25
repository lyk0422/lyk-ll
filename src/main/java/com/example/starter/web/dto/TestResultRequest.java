package com.example.starter.web.dto;

import com.example.starter.domain.TestResultCode;
import com.example.starter.domain.TestType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 检测结果回传请求。初检与复检独立回传，允许乱序。
 *
 * @param barcode    成分条码
 * @param testType   INITIAL 初检 / REPEAT 复检
 * @param result     PASS 合格 / FAIL 不合格
 * @param receivedAt 回传时刻(UTC)，为空时以服务端当前时刻为准
 */
public record TestResultRequest(
        @NotBlank String barcode,
        @NotNull TestType testType,
        @NotNull TestResultCode result,
        Instant receivedAt) {
}
