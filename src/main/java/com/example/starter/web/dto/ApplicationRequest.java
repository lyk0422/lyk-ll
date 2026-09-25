package com.example.starter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 申报请求：以 requestId 幂等，指纹覆盖 requestId 与全部业务字段。
 *
 * @param requestId   幂等请求ID
 * @param batchNo     批号（合成数据）
 * @param enterprise  生产企业名称
 * @param productName 品种名称
 * @param documents   已提交资料目录项
 * @param inspections 检验项目及测定值
 */
public record ApplicationRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotBlank String enterprise,
        @NotBlank String productName,
        @NotNull List<@Valid DocumentItem> documents,
        @NotNull List<@Valid InspectionItem> inspections) {

    /**
     * 资料提交项。
     *
     * @param itemCode 资料目录项编码
     */
    public record DocumentItem(@NotBlank String itemCode) {
    }

    /**
     * 检验项目。
     *
     * @param itemCode      检验项目编码
     * @param itemName      检验项目名称（关键项目以目录名称为准时可空）
     * @param limitValue    限值，测定值小于等于限值判合格
     * @param measuredValue 测定值
     */
    public record InspectionItem(@NotBlank String itemCode,
                                 String itemName,
                                 @NotNull BigDecimal limitValue,
                                 @NotNull BigDecimal measuredValue) {
    }
}
