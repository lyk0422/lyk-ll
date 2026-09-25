package com.example.starter.web.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 成分血入库请求。
 *
 * @param barcode    唯一条码
 * @param expiryDate 效期当日（UTC 日期，格式 yyyy-MM-dd）
 */
public record InboundRequest(
        @NotBlank String barcode,
        @NotNull LocalDate expiryDate) {
}
