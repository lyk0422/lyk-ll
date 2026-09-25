package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 押运打卡请求：两名不同押运员先后打卡，按到达时刻定序；同人重复打卡拒绝。
 */
public record CheckinRequest(
        @NotBlank String requestId,
        @NotBlank String custodianCode
) {
}
