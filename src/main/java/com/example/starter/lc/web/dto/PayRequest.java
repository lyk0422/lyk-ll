package com.example.starter.lc.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 银行付款指令。付款为终态：重复指令返回首次结果，不重复核减额度。
 */
public record PayRequest(
        @NotBlank String requestId,
        @NotBlank String presentationNo) {
}
