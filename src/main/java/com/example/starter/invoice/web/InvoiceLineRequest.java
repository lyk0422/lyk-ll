package com.example.starter.invoice.web;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 发票行项目请求。
 *
 * @param itemName  项目名称
 * @param quantity  数量，须为正
 * @param unitPrice 单价，单位：元，须非负
 * @param amount    行金额，单位：元，须等于数量×单价且为正
 */
public record InvoiceLineRequest(
        @NotBlank @Size(max = 128) String itemName,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
        @NotNull @DecimalMin(value = "0") BigDecimal unitPrice,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {
}
