package com.example.starter.deicing.api;

import com.example.starter.deicing.model.DeiceType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 除冰申请请求体。所有时刻字段均为 UTC 时区 epoch 毫秒。
 *
 * @param requestId          申请幂等键，由客户端给定
 * @param flightNo           航班号
 * @param stand              机位号
 * @param deiceType          除冰类型：ONE_STEP 一步法 / TWO_STEP 两步法
 * @param requiredBays       所需车位数（1 或 2）
 * @param targetDepartureTs  目标起飞时刻（UTC epoch 毫秒）
 */
public record ApplyRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 32) String flightNo,
        @NotBlank @Size(max = 32) String stand,
        @NotNull DeiceType deiceType,
        @Min(1) int requiredBays,
        @NotNull Long targetDepartureTs
) {
}
