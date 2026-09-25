package com.example.starter.deicing.api;

import jakarta.validation.constraints.NotNull;

/**
 * 起飞回报请求体。
 *
 * @param requestId          申请幂等键
 * @param actualDepartureTs  实际起飞时刻（UTC epoch 毫秒）
 */
public record DepartRequest(
        @NotNull String requestId,
        @NotNull Long actualDepartureTs
) {
}
