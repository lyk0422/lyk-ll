package com.example.starter.dispatch.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 单门执行回执请求。
 *
 * @param gateCode       回执闸门编码
 * @param actualOpening  实际开度，百分比 0~100，单位 %
 */
public record ReceiptRequest(
        @NotBlank String gateCode,
        @NotNull BigDecimal actualOpening) {
}
