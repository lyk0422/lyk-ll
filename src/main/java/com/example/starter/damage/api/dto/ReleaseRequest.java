package com.example.starter.damage.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 放行请求：放箱与残损案结案在同一事务内完成。 */
public record ReleaseRequest(
        @NotBlank String requestId,
        @NotBlank String caseNo,
        /** 放行时刻（UTC 毫秒）；为空取服务端当前时刻。 */
        Long releasedAtUtc) {
}
