package com.example.starter.metro.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 缴回对账单建立请求：仅非空（含负向调整明细）的已封存调账批次可建立，
 * 每个调账批次至多建立一张缴回对账单。
 *
 * @param requestId  幂等键
 * @param statementNo 缴回对账单号（全局唯一）
 * @param batchNo    来源已封存调账批次号
 * @param createTime 建立时刻（Asia/Shanghai）
 */
public record RemittanceOpenRequest(
        @NotBlank String requestId,
        @NotBlank String statementNo,
        @NotBlank String batchNo,
        @NotNull LocalDateTime createTime) {
}
