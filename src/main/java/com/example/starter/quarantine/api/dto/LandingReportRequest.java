package com.example.starter.quarantine.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 落地报告。幂等键为 requestId+reportId；指纹覆盖 requestId、reportId 与数量。
 *
 * @param requestId 关联申报键
 * @param reportId  本批落地报告业务键，支持同一申报分批到达
 * @param quantity  本批到达数量，单位 头/只
 */
public record LandingReportRequest(
        @NotBlank String requestId,
        @NotBlank String reportId,
        @Min(1) int quantity) {
}
