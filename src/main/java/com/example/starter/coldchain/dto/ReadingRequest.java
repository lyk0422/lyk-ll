package com.example.starter.coldchain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 写温度读数请求。
 *
 * @param sequenceNo   设备采样序号，货件内全局严格递增
 * @param deviceNo     设备序号（设备标识）
 * @param sampledAt    采样时间（Asia/Shanghai）
 * @param temperature  采样温度（摄氏度，须为有限数值）
 */
public record ReadingRequest(
        @NotNull Long sequenceNo,
        @NotBlank String deviceNo,
        @NotNull LocalDateTime sampledAt,
        @NotNull Double temperature
) {
}
