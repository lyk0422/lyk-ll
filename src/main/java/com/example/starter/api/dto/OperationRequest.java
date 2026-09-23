package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 携带请求键、期望版本与业务时间的状态操作请求（审批/激活/完成/取消共用）。
 * 上述全部参数参与请求指纹。
 */
public record OperationRequest(
        @NotNull Integer expectedVersion,
        @NotNull Instant businessTime
) {
}
