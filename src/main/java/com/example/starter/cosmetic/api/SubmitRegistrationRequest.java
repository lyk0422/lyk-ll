package com.example.starter.cosmetic.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 首次备案提交请求；requestId 为幂等键，指纹覆盖 requestId 与全部业务字段。
 */
public record SubmitRegistrationRequest(
        @NotBlank(message = "requestId 必填") String requestId,
        @NotNull(message = "产品ID必填") Long productId,
        @NotBlank(message = "包装文字必填") String packagingText,
        List<String> claims,
        List<IngredientPayload> ingredients,
        List<ReportPayload> reports) {
}
