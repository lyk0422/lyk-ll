package com.example.starter.gas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 气质差异签认请求。
 *
 * @param requestId 幂等键
 * @param party     签认方：A/B
 */
public record DifferenceSignOffRequest(
        @NotBlank String requestId,
        @NotBlank @Pattern(regexp = "A|B") String party) {
}
