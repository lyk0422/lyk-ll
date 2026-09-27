package com.example.starter.cosmetic.child.web.dto;

import com.example.starter.cosmetic.web.dto.FormulationItemRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 儿童备案首次提交请求。
 *
 * <p>年龄以整数月闭区间表示，满足 0≤下限≤上限≤144（Bean Validation 保证边界，
 * 服务端再校验下限≤上限，非法返回 400）。</p>
 *
 * @param requestId     幂等键
 * @param atUtc         调用方给出的精确受理时刻(UTC)，参与指纹；同键同参（含同一时刻）重放首次结果，不同时刻视为异参
 * @param productCode   产品编码
 * @param productName   产品名称
 * @param ageFromMonths 申请年龄下限(整数月，闭区间)
 * @param ageToMonths   申请年龄上限(整数月，闭区间)
 * @param formulation   配方（成分及质量百分比浓度）
 * @param claims        儿童功效宣称编码列表（仅允许合成儿童功效目录）
 * @param safetyReports 分档独立安全报告列表（跨档须两档各一份且覆盖申请区间）
 * @param ageLabelText  适用年龄标识文本（须明确标注适用年龄）
 */
public record ChildSubmitFilingRequest(
        @NotBlank String requestId,
        @NotNull Instant atUtc,
        @NotBlank String productCode,
        @NotBlank String productName,
        @NotNull @Min(0) @Max(144) Integer ageFromMonths,
        @NotNull @Min(0) @Max(144) Integer ageToMonths,
        @NotEmpty @Valid List<FormulationItemRequest> formulation,
        @NotEmpty List<@NotBlank String> claims,
        @NotEmpty @Valid List<ChildSafetyReportRequest> safetyReports,
        @NotBlank String ageLabelText) {
}
