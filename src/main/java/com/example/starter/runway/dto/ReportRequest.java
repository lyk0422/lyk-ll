package com.example.starter.runway.dto;

import com.example.starter.runway.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 接报请求：接报 FOD 或道面破损即触发临时关闭。requestId 为幂等标识。
 *
 * @param requestId  幂等请求标识（指纹覆盖该字段与全部业务字段）
 * @param runwayId   跑道编号（合成数据）
 * @param eventType  事件类型：FOD / PAVEMENT_DAMAGE
 * @param reportedAt 接报时间（UTC，ISO-8601）
 */
public record ReportRequest(
        @NotBlank String requestId,
        @NotBlank String runwayId,
        @NotNull EventType eventType,
        @NotNull Instant reportedAt) {
}
