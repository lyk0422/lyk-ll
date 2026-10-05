package com.example.starter.fishery.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 年度配额结转请求。
 *
 * @param requestId 幂等键
 * @param vesselCode 渔船编号
 * @param speciesCode 鱼种编号
 * @param fromYear 结转来源年度（UTC），结转目标年度为 fromYear + 1
 */
public record CarryoverRequest(
        @NotBlank String requestId,
        @NotBlank String vesselCode,
        @NotBlank String speciesCode,
        @NotNull Integer fromYear) {
}
