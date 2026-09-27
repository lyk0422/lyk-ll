package com.example.starter.cosmetic.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 儿童备案材料补正请求（仅公示中的最新修订可补正）。
 *
 * <p>补正仅允许更新材料：儿童安全报告、标识文本、配方、产品名称。
 * 儿童属性（宣称）、申请年龄范围或标识适用范围的改变必须走实质变更，
 * 在补正中携带将被拒绝（422）。</p>
 *
 * @param requestId       幂等键
 * @param expectedVersion 期望版本号（当前版本，乐观并发控制；成功补正后版本加1）
 * @param productName     新产品名称，null 表示不变
 * @param formulation     新配方，null 表示不变
 * @param claims          宣称，null 表示不变；与当前不一致时拒绝（须走实质变更）
 * @param ageLowerMonths  申请年龄下限，null 表示不变；与当前不一致时拒绝（须走实质变更）
 * @param ageUpperMonths  申请年龄上限，null 表示不变；与当前不一致时拒绝（须走实质变更）
 * @param safetyReports   新儿童安全报告清单，null 表示不变
 * @param labels          新适用年龄标识清单，null 表示不变；标识适用范围改变时拒绝（须走实质变更）
 */
public record CorrectChildrenFilingRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        String productName,
        @Valid List<FormulationItemRequest> formulation,
        List<@NotBlank String> claims,
        @Min(0) @Max(144) Integer ageLowerMonths,
        @Min(0) @Max(144) Integer ageUpperMonths,
        @Valid List<ChildrenSafetyReportRequest> safetyReports,
        @Valid List<AgeLabelRequest> labels) {
}
