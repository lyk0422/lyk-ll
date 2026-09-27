package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 紧急关闭接报请求：同事务抢占该跑道尚未开始/已开始的例行计划。requestId 幂等。
 *
 * @param requestId 幂等请求标识
 * @param runwayId  跑道编号
 * @param reason    紧急关闭原因（合成文本，允许包含分隔符）
 * @param startedAt 紧急关闭生效时刻（UTC，精确时刻参与指纹）
 */
public record EmergencyReportRequest(
        @NotBlank String requestId,
        @NotBlank String runwayId,
        @NotBlank String reason,
        @NotNull Instant startedAt) {
}
