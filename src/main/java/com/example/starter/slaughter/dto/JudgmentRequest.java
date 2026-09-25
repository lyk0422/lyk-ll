package com.example.starter.slaughter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 判定分流请求：对全部已检验头只逐头判定，判定与库存标识在同一事务内完成。
 *
 * @param requestId 幂等键
 * @param items     逐头判定明细
 */
public record JudgmentRequest(
        @NotBlank String requestId,
        @NotEmpty List<@Valid JudgmentItem> items) {
}
