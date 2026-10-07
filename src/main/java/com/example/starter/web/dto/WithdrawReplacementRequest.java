package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 热备接替撤回请求。仅未切换（STANDBY）可撤回；释放全部新车预占而保留旧车供电。
 *
 * @param requestId       幂等请求号
 * @param expectedVersion 期望的接替单版本号
 */
public record WithdrawReplacementRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion) {
}
