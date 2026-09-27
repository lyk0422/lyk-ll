package com.example.starter.cosmetic.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 儿童备案材料补正请求。
 *
 * <p>补正生成新修订（版本号 +1）并使旧修订待审批准失效；公示只能使用最新修订。
 * 未提供的字段视为与当前最新修订一致。审查不通过时新修订为待补正状态，
 * 可再次补正；任一缺项一次列全并零生效（不进入公示）。</p>
 *
 * @param requestId       幂等键；指纹覆盖 requestId、expectedVersion 与全部业务字段
 * @param expectedVersion 期望的当前最新版本号（乐观并发控制，不一致返回 409）
 * @param productName     新产品名称，null 表示不变
 * @param formulation     新配方，null 表示不变
 * @param claims          新功效宣称列表，null 表示不变
 * @param reports         新成人功效评价试验报告列表，null 表示沿用
 * @param packagingText   新包装文字，null 表示不变
 * @param ageLowerMonths  新申请年龄下限（整数月），null 表示不变
 * @param ageUpperMonths  新申请年龄上限（整数月），null 表示不变
 * @param childDossiers   新分龄档材料清单（安全报告+适用年龄标识），null 表示不变
 */
public record CorrectionFilingRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        String productName,
        @Valid List<FormulationItemRequest> formulation,
        List<@NotBlank String> claims,
        @Valid List<TestReportRequest> reports,
        String packagingText,
        Integer ageLowerMonths,
        Integer ageUpperMonths,
        @Valid List<ChildBandDossierRequest> childDossiers) {
}
