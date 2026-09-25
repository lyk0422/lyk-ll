package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 偏差告警与确认相关请求/响应。实际温湿度连续 3 个采样点偏离设定带即触发（恰在第 3 点）。
 * 告警确认以 requestId 幂等。
 */
public final class AlarmDtos {

    private AlarmDtos() {
    }

    public record AckRequest(
            @NotNull Long alarmId,
            @NotBlank @Size(max = 96) String requestId) {
    }

    public record AlarmResponse(
            Long alarmId,
            String zoneCode,
            String strategyCode,
            Instant startSampleTime,
            Instant triggerTime,
            int readingCount,
            String status,
            String ackRequestId,
            Instant createdAt,
            Instant acknowledgedAt) {
    }
}
