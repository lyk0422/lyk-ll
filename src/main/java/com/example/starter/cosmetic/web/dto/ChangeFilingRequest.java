package com.example.starter.cosmetic.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 备案变更请求。
 *
 * <p>变更类型由服务端比对当前有效备案自动判定：配方或功效宣称发生变化为实质变更
 * （重新审查并生成新备案编号）；仅包装文字变化为简易变更（引用原编号）。
 * 未提供的字段视为与当前有效备案一致。</p>
 *
 * <p>儿童备案：儿童属性（类别）、申请年龄范围或标识适用范围（分龄档材料清单）
 * 的改变一律走实质变更，不得伪装为包装文字简易变更。儿童备案变更必须携带
 * expectedVersion（当前有效版本号），不一致返回 409。</p>
 *
 * @param requestId       幂等键；指纹覆盖 requestId 与全部业务字段
 * @param productName     新产品名称，null 表示不变
 * @param formulation     新配方，null 表示不变；非 null 即按实质变更比对
 * @param claims          新功效宣称列表，null 表示不变
 * @param reports         新配方/宣称对应的试验报告列表，null 表示沿用原报告
 * @param packagingText   新包装文字；仅包装文字类变更走简易变更
 * @param expectedVersion 期望的当前有效版本号（儿童备案变更必填；成人备案为 null 时不校验）
 * @param filingCategory  目标备案类别：ADULT / CHILD，null 表示不变
 * @param ageLowerMonths  儿童备案申请年龄下限（整数月），null 表示不变
 * @param ageUpperMonths  儿童备案申请年龄上限（整数月），null 表示不变
 * @param childDossiers   儿童备案分龄档材料清单，null 表示不变
 */
public record ChangeFilingRequest(
        @NotBlank String requestId,
        String productName,
        @Valid List<FormulationItemRequest> formulation,
        List<@NotBlank String> claims,
        @Valid List<TestReportRequest> reports,
        String packagingText,
        Integer expectedVersion,
        @Pattern(regexp = "ADULT|CHILD") String filingCategory,
        @Min(0) @Max(144) Integer ageLowerMonths,
        @Min(0) @Max(144) Integer ageUpperMonths,
        @Valid List<ChildBandDossierRequest> childDossiers) {
}
