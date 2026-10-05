package com.example.starter.flood.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 替代建议人工确认请求（requestId 幂等，指纹覆盖全部业务字段）。
 *
 * @param requestId 幂等键
 * @param operator 确认人
 * @param decision 决策：ACCEPT_NEW_VERSION 按新版本下达 / KEEP_CURRENT 维持在途指令
 * @param note 确认备注，可空
 */
public record AdviceConfirmRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank @Pattern(regexp = "ACCEPT_NEW_VERSION|KEEP_CURRENT") String decision,
        String note) {
}
