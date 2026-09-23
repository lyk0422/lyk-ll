package com.example.starter.specimen.web.dto;

import com.example.starter.specimen.model.EntityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * 保管交接请求：requestId 为幂等键；fromCustodian 须为当前持有人；
 * eventAt 不得早于该实体上一保管事件时间；重复交接不新增事件。
 */
public record TransferRequest(
        @NotBlank String requestId,
        @NotNull EntityType entityType,
        @NotBlank String entityId,
        @NotBlank String fromCustodian,
        @NotBlank String toCustodian,
        @PositiveOrZero long expectedVersion,
        @NotNull Instant eventAt) {
}
