package com.example.starter.gas.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;

/**
 * 阀门双人确认指令请求。LOCK 锁阀；REOPEN 复开（同事务解除告警）。
 * 两位确认人必须同时存在且不同，单人操作不生效。
 */
public record ValveCommandRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotBlank @Pattern(regexp = "LOCK|REOPEN") String action,
        @NotBlank String operator1,
        String operator2,
        @NotNull @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime occurredAt
) {
}
