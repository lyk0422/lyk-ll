package com.example.starter.cosmetic.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * 备案变更请求；changeType 取 SUBSTANTIAL（实质变更，重新审查并生成新编号）或 SIMPLE（简易变更，引用原编号）。
 */
public record SubmitChangeRequest(
        @NotBlank(message = "requestId 必填") String requestId,
        @NotBlank(message = "变更类型必填") String changeType,
        String packagingText,
        List<String> claims,
        List<IngredientPayload> ingredients,
        List<ReportPayload> reports) {
}
