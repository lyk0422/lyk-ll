package com.example.starter.feed.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 配方批准请求；仅 SUBMITTED（合规校验通过）版本可批准，批准后内容冻结。
 *
 * @param requestId   幂等键
 * @param formulaCode 配方编码
 * @param versionNo   待批准版本号
 */
public record ApproveFormulaRequest(
        @NotBlank String requestId,
        @NotBlank String formulaCode,
        @NotNull Integer versionNo) {
}
