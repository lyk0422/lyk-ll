package com.example.starter.flood.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 复盘归档请求；归档为终态，重复归档返回首次结果（幂等成功）。
 *
 * @param requestId 幂等键
 * @param warningNo 处置链预警编号
 * @param note 复盘结论/备注，可空
 */
public record ReviewRequest(
        @NotBlank String requestId,
        @NotBlank String warningNo,
        String note) {
}
