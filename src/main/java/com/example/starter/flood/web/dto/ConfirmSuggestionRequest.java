package com.example.starter.flood.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 人工确认替代建议请求（requestId 幂等，同键异参 409）。
 *
 * @param requestId 幂等键
 * @param operator 确认人
 * @param decision 决策：ADOPT_NEW_VERSION 采纳新版本 / KEEP_ORIGINAL_VERSION 维持原版本
 * @param note 确认说明，可空
 */
public record ConfirmSuggestionRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank @Pattern(regexp = "ADOPT_NEW_VERSION|KEEP_ORIGINAL_VERSION") String decision,
        String note) {
}
