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
 * 儿童备案材料补正请求：生成新修订并使旧修订待审批准失效。
 *
 * <p>补正为整体替换式提交（全部材料字段必填），受理时重新冻结
 * 报告清单、分龄规则版本与标识文本摘要；公示只能使用最新修订。</p>
 *
 * @param requestId       幂等键（指纹含 expectedVersion、集合与精确时刻）
 * @param expectedVersion 期望的聚合版本（乐观锁，不一致业务拒绝 422）
 * @param ageLowerMonths  申请年龄下限（整数月，含，0~144）
 * @param ageUpperMonths  申请年龄上限（整数月，含，0~144）
 * @param claims          儿童功效宣称编码列表
 * @param formulation     配方
 * @param safetyReports   分档儿童安全报告列表
 * @param labels          分档适用年龄标识列表
 * @param packagingText   包装文字（可选）
 * @param submittedAtUtc  提交精确时刻(UTC)，可选；为空取服务端时钟
 */
public record ChildCorrectionRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotNull @Min(0) @Max(144) Integer ageLowerMonths,
        @NotNull @Min(0) @Max(144) Integer ageUpperMonths,
        @NotEmpty List<@NotBlank String> claims,
        @NotEmpty @Valid List<FormulationItemRequest> formulation,
        @Valid List<ChildSafetyReportRequest> safetyReports,
        @Valid List<ChildLabelRequest> labels,
        String packagingText,
        Instant submittedAtUtc) {
}
