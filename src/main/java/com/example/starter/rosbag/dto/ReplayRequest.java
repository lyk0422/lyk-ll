package com.example.starter.rosbag.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 按原顺序回放请求；replayKey 相同的重复请求不会重复创建回放记录。
 */
public record ReplayRequest(
        @NotBlank String replayKey,
        @NotBlank String topic,
        Long fromNs,
        Long toNs
) {
}
