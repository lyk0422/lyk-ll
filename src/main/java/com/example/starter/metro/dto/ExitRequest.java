package com.example.starter.metro.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 出闸请求；对已暂记行程提交出闸即补登，同一事务内冲销暂记并按实结算。
 *
 * @param requestId 幂等键
 * @param txnNo     交易流水号
 * @param station   出闸站编码
 * @param exitTime  出闸时间（Asia/Shanghai）
 */
public record ExitRequest(
        @NotBlank String requestId,
        @NotBlank String txnNo,
        @NotBlank String station,
        @NotNull LocalDateTime exitTime) {
}
