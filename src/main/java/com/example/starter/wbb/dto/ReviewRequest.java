package com.example.starter.wbb.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 单岗复核签字请求。WBO 与 DSP 两岗由不同人员先后签字，齐签后舱单复核通过并冻结。
 */
public record ReviewRequest(
        @NotBlank String requestId,
        @NotBlank String role,
        @NotBlank String reviewer
) {
}
