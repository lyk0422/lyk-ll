package com.example.starter.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 方案变更申请：在同一潜次上以新深度/新水底时长重查减压表，
 * 与已生成旧方案记录的冻结在同一事务内完成。以 requestId 幂等。
 */
public record PlanChangeRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 40) String operationNo,
        @NotNull @Min(1) Integer depthM,
        @NotNull @Min(1) Integer bottomMinutes) {
}
