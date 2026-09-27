package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 列尾装置建档请求（合成数据，仅 JVM 生命周期内保留）。
 *
 * @param requestId           幂等请求键
 * @param deviceNo            装置编号
 * @param selfCheckValidUntil 自检合格有效截止时刻（UTC，精确时刻；UTC 下 now &lt; 该时刻有效，恰达失效）
 */
public record EotDeviceRegisterRequest(
        @NotBlank String requestId,
        @NotBlank String deviceNo,
        @NotNull Instant selfCheckValidUntil) {
}
