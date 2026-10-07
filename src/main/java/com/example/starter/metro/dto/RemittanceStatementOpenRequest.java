package com.example.starter.metro.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 缴回对账单建立请求：仅非空已封存调账批次可建立，每批最多一单。
 *
 * @param requestId   幂等键
 * @param statementNo 对账单号（全局唯一）
 * @param batchNo     来源调账批次号（须已封存且非空）
 * @param openTime    建立精确时刻（Asia/Shanghai）
 */
public record RemittanceStatementOpenRequest(
        @NotBlank String requestId,
        @NotBlank String statementNo,
        @NotBlank String batchNo,
        @NotNull LocalDateTime openTime) {
}
