package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 协查单生成请求。
 *
 * @param requestId       幂等键，非空；指纹覆盖 requestId 与全部参数
 * @param institutionCode 协查推送机构编码，非空
 * @param note            协查说明，可空
 */
public record CreateInvestigationRequest(
        @NotBlank String requestId,
        @NotBlank String institutionCode,
        String note) {
}
