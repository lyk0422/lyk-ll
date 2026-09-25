package com.example.starter.slaughter.dto;

import com.example.starter.slaughter.domain.PreQuarantineResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 宰前检疫登记请求。
 *
 * @param requestId 幂等键
 * @param result    检疫结论 PASS/FAIL
 */
public record PreQuarantineRequest(
        @NotBlank String requestId,
        @NotNull PreQuarantineResult result) {
}
