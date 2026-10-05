package com.example.starter.fishery.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 年度结转请求：上年未用配额按不超过 20% 结转至次年。
 *
 * @param requestId 幂等键（指纹覆盖 requestId 与全部业务字段）
 * @param vesselCode 渔船编号
 * @param speciesCode 鱼种编号
 * @param sourceYear 结转来源年度（UTC），目标年度为 sourceYear+1
 */
public record CarryoverRequest(
        @NotBlank String requestId,
        @NotBlank String vesselCode,
        @NotBlank String speciesCode,
        @NotNull Integer sourceYear) {
}
