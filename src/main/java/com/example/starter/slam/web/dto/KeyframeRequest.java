package com.example.starter.slam.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 写入关键帧请求。
 *
 * @param version     来源系统内的版本号，从 0 起单调递增
 * @param measurement 关键帧测量数据（任意 JSON 结构，参与内容判重）
 */
public record KeyframeRequest(
        @NotBlank String id,
        @NotBlank String source,
        @Min(0) int version,
        double x,
        double y,
        double theta,
        @NotNull JsonNode measurement,
        @NotBlank String requestId
) {
}
