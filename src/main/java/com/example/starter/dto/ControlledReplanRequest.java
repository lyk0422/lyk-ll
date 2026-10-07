package com.example.starter.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

/**
 * 受控分段计划修改（重规划）请求：从当前实际功率重新形成守斜率的剩余计划。
 * 目标沿用当前轮次分摊结果；成功版本加 1，旧版 409；失败不占键、版本不变。
 *
 * @param requestId       幂等键，覆盖全部业务字段的结构化指纹
 * @param expectedVersion 期望修改的当前计划版本号，须等于最新版本
 * @param turbines        全部参与机组的新斜率与期限
 */
public record ControlledReplanRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion,
        @NotEmpty @Valid List<ReplanTurbineInput> turbines) {
}
