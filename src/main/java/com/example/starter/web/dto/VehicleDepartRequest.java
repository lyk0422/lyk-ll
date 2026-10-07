package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 逐车核销（发车出厂）请求：事务内重查原全批抽检及处置门禁、该车清单和版本，
 * 全部满足才把本车冻结头只一次性转已出厂；单车不得部分核销，失败保留原预占。
 *
 * @param requestId       幂等请求标识，全局唯一
 * @param expectedVersion 车单期望版本，须与当前版本一致，核销成功后版本加 1
 * @param occurredAt      业务精确时刻（UTC ISO-8601），参与结构化指纹
 * @param operator        核销经办人（合成数据）
 */
public record VehicleDepartRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotBlank String occurredAt,
        @NotBlank String operator) {
}
