package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 同步检验请求：按头只编号一一对应登记。
 *
 * @param requestId 幂等请求标识
 * @param headNo    头只编号
 * @param result    检验结果（如 NORMAL/ABNORMAL，合成数据）
 */
public record InspectionRequest(
        @NotBlank String requestId,
        @NotBlank String headNo,
        @NotBlank String result) {
}
