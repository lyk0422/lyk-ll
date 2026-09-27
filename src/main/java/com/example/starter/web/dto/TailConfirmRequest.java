package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 列尾装置与最新编组尾车一致性共同确认请求，须两名不同人员共同确认。
 *
 * @param requestId       幂等请求键
 * @param expectedVersion 装置期望版本（乐观锁），成功确认后版本加 1
 * @param confirmer1      第一名确认人员
 * @param confirmer2      第二名确认人员，须与第一名不同
 * @param confirmedAt     共同确认时刻（UTC 精确时刻）
 */
public record TailConfirmRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotBlank String confirmer1,
        @NotBlank String confirmer2,
        @NotNull Instant confirmedAt) {
}
