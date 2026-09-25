package com.example.starter.telemetry;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 遥信回报接口报文定义。
 */
public final class TelemetryDtos {

    private TelemetryDtos() {
    }

    /** 遥信回报请求：按间隔＋时标去重。 */
    public record TelemetryRequest(
            @NotBlank String intervalId,
            @NotBlank String deviceId,
            @NotNull Instant ts,
            @NotBlank String state) {
    }

    /**
     * 遥信处理结果。
     *
     * @param telemetryId 遥信记录主键
     * @param result      VERIFIED核对一致/ABORTED不符中止/RECORDED仅记录/LATE迟到事件/DEDUPLICATED重复去重
     * @param ticketId    关联操作票主键，无关联为空
     */
    public record TelemetryResponse(Long telemetryId, String result, Long ticketId) {
    }
}
