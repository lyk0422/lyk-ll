package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 轧辊里程台账日冻结请求。
 *
 * @param requestId 幂等键，指纹覆盖该键与目标轧辊号
 */
public record RollFreezeRequest(
        @NotBlank String requestId) {
}
