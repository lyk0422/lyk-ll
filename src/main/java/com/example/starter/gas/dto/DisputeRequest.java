package com.example.starter.gas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 争议发起请求。
 */
public record DisputeRequest(
        @NotBlank String requestId,
        @NotBlank @Pattern(regexp = "A|B") String party,
        @NotBlank @Size(max = 500) String reason) {
}
