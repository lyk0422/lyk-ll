package com.example.starter.hazmat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 撤销人员资格预约请求（乐观锁修改；仅首次换班打卡前可撤销）。
 *
 * @param requestId       幂等键
 * @param expectedVersion 期望的当前预约版本号；不一致返回 409
 */
public record ReservationRevokeRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion) {
}
