package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 启用分车出厂请求。仅“有合格头只且尚未出厂”的批次可启用；启用为单向标志，幂等受理。
 *
 * @param requestId  幂等请求标识，全局唯一
 * @param operator   经办人（合成数据）
 * @param occurredAt 业务精确时刻（UTC ISO-8601），参与结构化指纹
 */
public record SplitEnableRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String occurredAt) {
}
