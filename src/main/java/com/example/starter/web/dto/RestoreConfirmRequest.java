package com.example.starter.web.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 复电确认请求。role 取 DISPATCHER（调度）或 FIELD（现场）；现场确认必须携带恢复负荷 loadKw。
 *
 * @param requestId   幂等请求号
 * @param workOrderId 工单ID
 * @param role        确认角色：DISPATCHER/FIELD
 * @param confirmer   确认人
 * @param loadKw      恢复负荷，单位kW；现场角色必填
 */
public record RestoreConfirmRequest(
        @NotBlank String requestId,
        @NotNull Long workOrderId,
        @NotBlank String role,
        @NotBlank String confirmer,
        BigDecimal loadKw) {
}
