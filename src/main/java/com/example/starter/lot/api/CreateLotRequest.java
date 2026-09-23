package com.example.starter.lot.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 创建批次请求。
 *
 * @param lotId    批次唯一标识
 * @param unit     计量单位
 * @param quantity 初始数量，固定三位小数且必须为正
 * @param remark   备注（可空）
 */
public record CreateLotRequest(
        @NotBlank @Size(max = 64) String lotId,
        @NotBlank @Size(max = 16) String unit,
        @NotNull @DecimalMin(value = "0.001", message = "数量必须为正") BigDecimal quantity,
        @Size(max = 255) String remark
) {
}
