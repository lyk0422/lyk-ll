package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 污染隔离请求：将 aliquot 置为 QUARANTINED，未开始申请联动 CANCELLED。
 *
 * @param expectedVersion aliquot 期望版本
 * @param expectedCustodian 期望当前保管人，必填且必须与当前持有人一致
 * @param quarantineCustodian 隔离保管人；为空使用默认 QUARANTINE
 */
public record QuarantineRequest(
        @NotBlank(message = "不能为空") String aliquotId,
        @NotNull(message = "不能为空") Integer expectedVersion,
        @NotBlank(message = "不能为空") String expectedCustodian,
        String quarantineCustodian,
        String note) {
}
