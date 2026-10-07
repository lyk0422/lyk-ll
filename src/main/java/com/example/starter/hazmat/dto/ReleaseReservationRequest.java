package com.example.starter.hazmat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 撤销人员资格预约请求（乐观版本控制）：仅首次换班打卡前可撤销并释放。
 *
 * @param requestId      幂等键（失败不占键）
 * @param expectedVersion 期望预约版本号：与当前版本一致方可撤销，成功加 1，过期 409
 */
public record ReleaseReservationRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion) {
}
