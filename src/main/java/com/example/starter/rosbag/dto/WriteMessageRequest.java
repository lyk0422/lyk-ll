package com.example.starter.rosbag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单条消息写入请求。batchNo 用于同参重试幂等。
 */
public record WriteMessageRequest(
        @NotBlank String topic,
        @NotNull Long seq,
        @NotNull Long timestampNs,
        @NotBlank String typeId,
        @NotNull String payload,
        @NotBlank String batchNo
) {
}
