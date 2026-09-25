package com.example.starter.runway.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 接报请求：FOD 或道面破损即触发临时关闭。
 *
 * @param requestId  幂等请求号
 * @param eventType  事件类型: FOD / PAVEMENT_DAMAGE
 * @param reportedAt 接报时间(UTC)，为空取服务端当前时间
 */
public record ReportClosureRequest(
        @NotBlank String requestId,
        @NotBlank @Pattern(regexp = "FOD|PAVEMENT_DAMAGE", message = "事件类型仅支持 FOD/PAVEMENT_DAMAGE") String eventType,
        Instant reportedAt) {
}
