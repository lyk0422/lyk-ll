package com.example.starter.gas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 凭证确认请求：交接方 A 或 B 各自确认。
 */
public record ConfirmRequest(
        @NotBlank String requestId,
        @NotBlank @Pattern(regexp = "A|B") String party) {
}
