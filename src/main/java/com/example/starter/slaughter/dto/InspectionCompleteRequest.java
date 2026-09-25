package com.example.starter.slaughter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 同步检验链收口请求：收口时复算头只覆盖情况，漏检则整批暂停。
 *
 * @param requestId 幂等键
 */
public record InspectionCompleteRequest(
        @NotBlank String requestId) {
}
