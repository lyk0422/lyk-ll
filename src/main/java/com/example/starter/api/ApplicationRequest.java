package com.example.starter.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 潜水作业申请：两人一组（diverA/diverB），申请深度（米）与计划水底时长（分钟）。
 * 以 requestId 幂等，指纹覆盖 requestId 与本记录全部业务字段。
 */
public record ApplicationRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String diverA,
        @NotBlank @Size(max = 64) String diverB,
        @NotNull @Min(1) Integer depthM,
        @NotNull @Min(1) Integer bottomMinutes) {
}
