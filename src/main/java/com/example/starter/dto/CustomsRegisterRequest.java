package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/**
 * 进口疫苗合成通关单登记请求。
 *
 * @param requestId      幂等键（完整参数含版本语义：登记成功后版本自 1 起）
 * @param declarationNo  合成通关单号（全局唯一）
 * @param enterpriseName 登记企业，绑定子批企业必须与此一致
 * @param productName    登记疫苗品种，绑定子批品种必须与此一致
 * @param validUntil     有效截止日期（ISO yyyy-MM-dd，UTC 日界判定，恰截止日拒绝签发）
 * @param totalQuantity  可核销总数量（正整数，单位：支）
 */
public record CustomsRegisterRequest(
        @NotBlank String requestId,
        @NotBlank String declarationNo,
        @NotBlank String enterpriseName,
        @NotBlank String productName,
        @NotNull LocalDate validUntil,
        @Positive @NotNull Integer totalQuantity
) {
}
