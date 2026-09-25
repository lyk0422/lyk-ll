package com.example.starter.deicing.api;

import jakarta.validation.constraints.NotNull;

/**
 * 前序延误回报：更新航班最新预计起飞时刻，系统据此重新评估 HOT 风险。
 *
 * @param requestId             申请幂等键
 * @param expectedDepartureTs   最新预计起飞时刻（UTC epoch 毫秒）
 */
public record DelayRequest(
        @NotNull String requestId,
        @NotNull Long expectedDepartureTs
) {
}
