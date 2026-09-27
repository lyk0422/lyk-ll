package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 合成列尾装置建档请求。
 *
 * @param requestId           幂等请求键
 * @param deviceNo            装置编号（合成数据），未知编号不得绑定
 * @param selfCheckExpiresAt 自检合格有效截止时刻（UTC 精确时刻）；now 小于该时刻才合格
 */
public record TailDeviceRegisterRequest(
        @NotBlank String requestId,
        @NotBlank String deviceNo,
        @NotNull Instant selfCheckExpiresAt) {
}
