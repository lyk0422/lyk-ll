package com.example.starter.cosmetic.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 儿童化妆品备案提交请求。
 *
 * @param requestId      幂等键；指纹覆盖 requestId 与全部业务字段（结构化无歧义）
 * @param productCode    产品编码
 * @param productName    产品名称
 * @param ageLowerMonths 申请年龄下限（整数月，闭区间，0~144）
 * @param ageUpperMonths 申请年龄上限（整数月，闭区间，0~144）
 * @param formulation    配方（成分及质量百分比浓度）
 * @param claims         儿童功效宣称编码列表（合成儿童功效目录）
 * @param safetyReports  儿童安全报告清单（每适用档独立；成人功效报告不能替代）
 * @param labels         适用年龄标识清单（每适用档独立）
 * @param adultReports   随附成人功效评价报告（仅登记，不能替代儿童安全报告）
 */
public record SubmitChildrenFilingRequest(
        @NotBlank String requestId,
        @NotBlank String productCode,
        @NotBlank String productName,
        @NotNull @Min(0) @Max(144) Integer ageLowerMonths,
        @NotNull @Min(0) @Max(144) Integer ageUpperMonths,
        @NotEmpty @Valid List<FormulationItemRequest> formulation,
        @NotEmpty List<@NotBlank String> claims,
        @Valid List<ChildrenSafetyReportRequest> safetyReports,
        @Valid List<AgeLabelRequest> labels,
        @Valid List<TestReportRequest> adultReports) {
}
