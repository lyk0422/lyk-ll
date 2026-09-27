package com.example.starter.cosmetic.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 儿童备案变更请求（仅生效备案可变更）。
 *
 * <p>儿童属性（宣称）、申请年龄范围或标识适用范围的改变一律走实质变更
 * （重新审查并公示），不能伪装为包装文字简易变更；仅包装文字/产品名称
 * 文字变化走简易变更。未提供的字段视为与当前有效备案一致。</p>
 *
 * @param requestId       幂等键
 * @param expectedVersion 期望版本号（当前版本，乐观并发控制；成功变更后版本加1）
 * @param productName     新产品名称，null 表示不变
 * @param ageLowerMonths  新申请年龄下限，null 表示不变
 * @param ageUpperMonths  新申请年龄上限，null 表示不变
 * @param formulation     新配方，null 表示不变
 * @param claims          新儿童功效宣称，null 表示不变
 * @param safetyReports   新儿童安全报告清单，null 表示不变
 * @param labels          新适用年龄标识清单，null 表示不变
 * @param packagingText   新包装文字，null 表示不变
 */
public record ChangeChildrenFilingRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        String productName,
        @Min(0) @Max(144) Integer ageLowerMonths,
        @Min(0) @Max(144) Integer ageUpperMonths,
        @Valid List<FormulationItemRequest> formulation,
        List<@NotBlank String> claims,
        @Valid List<ChildrenSafetyReportRequest> safetyReports,
        @Valid List<AgeLabelRequest> labels,
        String packagingText) {
}
