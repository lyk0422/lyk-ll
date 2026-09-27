package com.example.starter.cosmetic.child.web.dto;

import com.example.starter.cosmetic.web.dto.FormulationItemRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 儿童备案变更请求（仅生效备案可变更）。
 *
 * <p>版本门禁：儿童属性（功效宣称、配方、安全报告）、年龄范围或标识适用范围
 * 发生变化必须走实质变更（changeKind=SUBSTANTIVE）；声明简易变更（SIMPLE）
 * 但实际触及上述内容的，按伪装简易变更业务拒绝（422）。
 * 未提供的字段视为与当前生效修订一致。</p>
 *
 * @param requestId       幂等键（指纹含 expectedVersion、集合与精确时刻）
 * @param expectedVersion 期望的聚合版本（乐观锁，不一致业务拒绝 422）
 * @param changeKind      声明的变更类型：SIMPLE=仅包装文字类，SUBSTANTIVE=实质变更
 * @param productName     新产品名称，null 表示不变
 * @param ageLowerMonths  新年龄下限，null 表示不变
 * @param ageUpperMonths  新年龄上限，null 表示不变
 * @param claims          新儿童功效宣称，null 表示不变
 * @param formulation     新配方，null 表示不变
 * @param safetyReports   新分档安全报告，null 表示不变
 * @param labels          新分档适用年龄标识，null 表示不变
 * @param packagingText   新包装文字，null 表示不变
 * @param submittedAtUtc  提交精确时刻(UTC)，可选；为空取服务端时钟
 */
public record ChildChangeRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotBlank String changeKind,
        String productName,
        @Min(0) @Max(144) Integer ageLowerMonths,
        @Min(0) @Max(144) Integer ageUpperMonths,
        List<@NotBlank String> claims,
        @Valid List<FormulationItemRequest> formulation,
        @Valid List<ChildSafetyReportRequest> safetyReports,
        @Valid List<ChildLabelRequest> labels,
        String packagingText,
        Instant submittedAtUtc) {
}
