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
 * 儿童备案提交请求（首次提交，创建聚合版本 1）。
 *
 * <p>幂等：requestId 为幂等键，指纹覆盖 requestId 与全部业务字段
 * （含年龄范围、集合与精确时刻），同键同参重放首次冻结结果，同键异参 409。</p>
 *
 * @param requestId      幂等键
 * @param productCode    产品编码（同一产品仅允许一个儿童备案）
 * @param productName    产品名称
 * @param ageLowerMonths 申请年龄下限（整数月，含，0~144，须 ≤ 上限）
 * @param ageUpperMonths 申请年龄上限（整数月，含，0~144）
 * @param claims         儿童功效宣称编码列表（仅允许儿童合成目录内功效）
 * @param formulation    配方（成分及质量百分比浓度）
 * @param safetyReports  分档儿童安全报告列表；跨档须两档齐备
 * @param labels         分档适用年龄标识列表；跨档须两档齐备
 * @param packagingText  包装文字（可选）
 * @param submittedAtUtc 提交精确时刻(UTC)，可选；为空取服务端时钟
 */
public record ChildSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String productCode,
        @NotBlank String productName,
        @NotNull @Min(0) @Max(144) Integer ageLowerMonths,
        @NotNull @Min(0) @Max(144) Integer ageUpperMonths,
        @NotEmpty List<@NotBlank String> claims,
        @NotEmpty @Valid List<FormulationItemRequest> formulation,
        @Valid List<ChildSafetyReportRequest> safetyReports,
        @Valid List<ChildLabelRequest> labels,
        String packagingText,
        Instant submittedAtUtc) {
}
