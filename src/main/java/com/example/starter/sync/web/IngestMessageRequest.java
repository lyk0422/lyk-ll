package com.example.starter.sync.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.example.starter.sync.SensorType;

/**
 * 传感器消息接收请求。rawTs 为传感器时钟原始时戳(纪元毫秒)。
 */
public record IngestMessageRequest(
        @NotNull SensorType sensorType,
        @NotBlank String messageId,
        @NotNull Long rawTs) {
}
