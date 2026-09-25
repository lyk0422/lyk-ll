package com.example.starter.damage.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 修理批复请求：批复有效期 7 个自然日（UTC 日界）。 */
public record ApproveRepairRequest(
        @NotBlank String requestId,
        @NotBlank String caseNo,
        /** 批复时刻（UTC 毫秒）；为空取服务端当前时刻。 */
        Long approvedAtUtc) {
}
