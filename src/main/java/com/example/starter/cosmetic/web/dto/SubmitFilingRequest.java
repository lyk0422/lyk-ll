package com.example.starter.cosmetic.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 备案提交请求（首次备案）。
 *
 * @param requestId    幂等键；指纹覆盖 requestId 与全部业务字段
 * @param productCode  产品编码
 * @param productName  产品名称
 * @param formulation  配方（成分及质量百分比浓度）
 * @param claims       功效宣称编码列表（合成功效目录）
 * @param reports      特殊功效对应的功效评价试验报告列表；普通化妆品可不提供
 */
public record SubmitFilingRequest(
        @NotBlank String requestId,
        @NotBlank String productCode,
        @NotBlank String productName,
        @NotEmpty @Valid List<FormulationItemRequest> formulation,
        @NotEmpty List<@NotBlank String> claims,
        @Valid List<TestReportRequest> reports) {
}
