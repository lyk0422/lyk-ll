package com.example.starter.metro.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 日结封账请求。
 *
 * @param requestId 幂等键
 * @param bizDate   业务日期
 */
public record SealRequest(
        @NotBlank String requestId,
        @NotNull LocalDate bizDate) {
}
