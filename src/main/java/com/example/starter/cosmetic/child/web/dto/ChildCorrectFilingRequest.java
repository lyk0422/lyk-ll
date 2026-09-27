package com.example.starter.cosmetic.child.web.dto;

import com.example.starter.cosmetic.web.dto.FormulationItemRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/**
 * 儿童备案材料补正请求。
 *
 * <p>形式审查驳回后，按一次列全的缺项补齐分龄材料，沿用同备案编号生成新修订
 * （version+1），并使旧待审批准修订原子失效。字段均为全量提交（不支持局部合并）。</p>
 *
 * @param requestId       幂等键
 * @param expectedVersion 调用方持有的当前修订版本号，从 1 开始；与库内最新不一致返回 409
 * @param productName     产品名称
 * @param ageFromMonths   申请年龄下限(整数月，闭区间)
 * @param ageToMonths     申请年龄上限(整数月，闭区间)
 * @param formulation     全量配方
 * @param claims          全量儿童功效宣称
 * @param safetyReports   全量分档独立安全报告
 * @param ageLabelText    全量适用年龄标识文本
 */
public record ChildCorrectFilingRequest(
        @NotBlank String requestId,
        @NotNull Instant atUtc,
        @NotNull @Positive Integer expectedVersion,
        @NotBlank String productName,
        @NotNull @Min(0) @Max(144) Integer ageFromMonths,
        @NotNull @Min(0) @Max(144) Integer ageToMonths,
        @NotEmpty @Valid List<FormulationItemRequest> formulation,
        @NotEmpty List<@NotBlank String> claims,
        @NotEmpty @Valid List<ChildSafetyReportRequest> safetyReports,
        @NotBlank String ageLabelText) {
}
