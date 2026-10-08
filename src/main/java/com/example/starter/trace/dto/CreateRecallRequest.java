package com.example.starter.trace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建来源召回请求。requestId 为幂等键；蜂场必须已存在，花期精确匹配；
 * 同蜂场同花期最多一条 ACTIVE 召回。
 */
public record CreateRecallRequest(
        @NotBlank String requestId,
        @NotNull Long apiaryId,
        @NotBlank String flowerPeriod,
        @NotBlank String reason,
        @NotBlank String credential,
        @NotBlank String createdBy) {
}
