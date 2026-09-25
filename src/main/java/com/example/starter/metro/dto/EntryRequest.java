package com.example.starter.metro.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 进闸请求。
 *
 * @param requestId   幂等键
 * @param txnNo       交易流水号，全局唯一
 * @param cardId      票卡编号
 * @param station     进闸站编码
 * @param entryTime   进闸时间（Asia/Shanghai）
 */
public record EntryRequest(
        @NotBlank String requestId,
        @NotBlank String txnNo,
        @NotBlank String cardId,
        @NotBlank String station,
        @NotNull LocalDateTime entryTime) {
}
