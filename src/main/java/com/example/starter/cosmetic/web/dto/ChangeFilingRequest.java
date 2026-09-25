package com.example.starter.cosmetic.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 备案变更请求。
 *
 * <p>变更类型由服务端比对当前有效备案自动判定：配方或功效宣称发生变化为实质变更
 * （重新审查并生成新备案编号）；仅包装文字变化为简易变更（引用原编号）。
 * 未提供的字段视为与当前有效备案一致。</p>
 *
 * @param requestId     幂等键；指纹覆盖 requestId 与全部业务字段
 * @param productName   新产品名称，null 表示不变
 * @param formulation   新配方，null 表示不变；非 null 即按实质变更比对
 * @param claims        新功效宣称列表，null 表示不变
 * @param reports       新配方/宣称对应的试验报告列表，null 表示沿用原报告
 * @param packagingText 新包装文字；仅包装文字类变更走简易变更
 */
public record ChangeFilingRequest(
        @NotBlank String requestId,
        String productName,
        @Valid List<FormulationItemRequest> formulation,
        List<@NotBlank String> claims,
        @Valid List<TestReportRequest> reports,
        String packagingText) {
}
