package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 保管方交接请求：校验实体类型、当前持有人、期望版本、目标持有人。
 * 重复的同一交接幂等返回，不新增事件、不变更版本。
 */
public record TransferRequest(
        @NotBlank(message = "不能为空") String entityType,
        @NotBlank(message = "不能为空") String entityId,
        @NotBlank(message = "不能为空") String fromCustodian,
        @NotBlank(message = "不能为空") String toCustodian,
        @NotNull(message = "不能为空") Integer expectedVersion) {
}
