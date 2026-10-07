package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 途中异常登记请求；仅 DEPARTURE_CONFIRMED 且未销记作业可登记，同作业仅一条活动处置。
 *
 * @param requestId 幂等请求键
 * @param reason    途中异常现象说明（合成数据）
 */
public record IncidentReportRequest(
        @NotBlank String requestId,
        @NotBlank String reason) {
}
