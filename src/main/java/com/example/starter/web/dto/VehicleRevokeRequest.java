package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 车辆清单撤销请求：仅未发车（RESERVED）清单可整体撤销并释放全部预占，不能事后改单。
 *
 * @param requestId       幂等请求标识，全局唯一
 * @param expectedVersion 车单期望版本，须与当前版本一致，撤销成功后版本加 1
 * @param occurredAt      业务精确时刻（UTC ISO-8601），参与结构化指纹
 */
public record VehicleRevokeRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotBlank String occurredAt) {
}
