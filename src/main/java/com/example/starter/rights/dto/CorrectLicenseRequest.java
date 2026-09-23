package com.example.starter.rights.dto;

import com.example.starter.rights.LicenseMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 授权更正请求；{@code expectedVersion} 非空时作为乐观锁条件。
 */
public record CorrectLicenseRequest(
        @NotBlank String requestId,
        @NotBlank String licenseId,
        Integer expectedVersion,
        @NotNull Instant start,
        @NotNull Instant end,
        @NotNull LicenseMode mode) {
}
