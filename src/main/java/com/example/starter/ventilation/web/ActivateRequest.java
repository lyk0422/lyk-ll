package com.example.starter.ventilation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 火灾模式激活请求。
 *
 * @param requestId       幂等键
 * @param zoneCode        防火分区编码
 * @param smokeDirection  烟气流向假设：FORWARD 正向 / BACKWARD 反向
 * @param targetCondition 目标工况：SMOKE_EXHAUST 排烟 / PRESSURIZATION 正压送风
 */
public record ActivateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 32) String zoneCode,
        @NotBlank String smokeDirection,
        @NotBlank String targetCondition
) {
}
