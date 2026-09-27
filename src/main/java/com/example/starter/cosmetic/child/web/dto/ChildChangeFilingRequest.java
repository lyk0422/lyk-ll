package com.example.starter.cosmetic.child.web.dto;

import com.example.starter.cosmetic.web.dto.FormulationItemRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/**
 * 儿童备案变更请求（针对当前有效儿童备案）。
 *
 * <p>儿童属性、年龄区间或标识适用范围改变必须走实质变更（重新分龄审查、公示、生成新编号），
 * 不能伪装为包装文字简易变更；仅包装文字/产品名称文字变化才走简易变更。
 * 未提供（null）的字段视为与当前有效备案一致。</p>
 *
 * @param requestId       幂等键
 * @param expectedVersion 调用方持有的当前有效版本号，从 1 开始；不一致返回 409
 * @param productName     新产品名称，null 表示不变
 * @param ageFromMonths   新申请年龄下限(整数月，闭区间)，null 表示不变；改变即实质变更
 * @param ageToMonths     新申请年龄上限(整数月，闭区间)，null 表示不变；改变即实质变更
 * @param formulation     新配方，null 表示不变；改变即实质变更
 * @param claims          新儿童功效宣称，null 表示不变；改变即实质变更
 * @param safetyReports   新分档安全报告，null 表示沿用；实质变更时按新分档重新校验
 * @param ageLabelText    新适用年龄标识文本，null 表示不变；改变即实质变更
 * @param packagingText   新包装文字；仅包装文字类差异可走简易变更
 */
public record ChildChangeFilingRequest(
        @NotBlank String requestId,
        @NotNull Instant atUtc,
        @NotNull @Positive Integer expectedVersion,
        String productName,
        @Min(0) @Max(144) Integer ageFromMonths,
        @Min(0) @Max(144) Integer ageToMonths,
        @Valid List<FormulationItemRequest> formulation,
        List<@NotBlank String> claims,
        @Valid List<ChildSafetyReportRequest> safetyReports,
        String ageLabelText,
        String packagingText) {
}
