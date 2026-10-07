package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 分段计划修改请求：带 expectedVersion 乐观并发控制。
 * 从当前实际功率按新斜率重排剩余阶梯（目标不变），成功版本加 1，旧版 409；
 * 须恰好覆盖全部参与机组。
 *
 * @param requestId       幂等键，覆盖全部业务字段
 * @param expectedVersion 调用方持有的当前计划版本号
 * @param turbines        全部参与机组的新斜率与期限
 */
public record StagedPlanModifyRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion,
        @NotEmpty List<StagedSlopeInput> turbines) {
}
