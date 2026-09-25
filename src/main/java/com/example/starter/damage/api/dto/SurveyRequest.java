package com.example.starter.damage.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/** 检验登记请求：按合成 IICL 代码登记残损项。 */
public record SurveyRequest(
        @NotBlank String requestId,
        @NotBlank String caseNo,
        @NotBlank String surveyor,
        /** 检验时刻（UTC 毫秒）；为空取服务端当前时刻。 */
        Long surveyedAtUtc,
        @NotEmpty @Valid List<SurveyItemDto> items) {
}
