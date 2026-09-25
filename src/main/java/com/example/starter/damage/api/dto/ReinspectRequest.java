package com.example.starter.damage.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 复检申报：返修后仍不合格自动转全损评估。 */
public record ReinspectRequest(
        @NotBlank String requestId,
        @NotBlank String caseNo,
        @NotNull Boolean passed,
        /** 复检时刻（UTC 毫秒）；为空取服务端当前时刻。 */
        Long reinspectedAtUtc) {
}
