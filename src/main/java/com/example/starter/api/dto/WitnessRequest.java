package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * H 点见证请求：PRESENT 见证人到场；ABSENT 见证缺席（转等待）。
 */
public record WitnessRequest(
        @NotBlank String requestId,
        @NotBlank String cardNo,
        @NotNull @Pattern(regexp = "PRESENT|ABSENT") String action,
        String witnessName) {
}
