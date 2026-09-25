package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/**
 * 申报资料中携带的单项检验结果（合成检验链）。
 *
 * @param itemCode      检验项目代码
 * @param measuredValue 实测值；数值类项目给数值，定性项目给“无菌/有菌”“合格/不合格”
 */
public record InspectionInput(
        @NotBlank String itemCode,
        @NotBlank String measuredValue
) {

    /** 将数值类实测值解析为 BigDecimal，失败返回 null。 */
    public BigDecimal decimalValue() {
        try {
            return new BigDecimal(measuredValue.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
