package com.example.starter.rights.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤销授权请求；{@code expectedVersion} 非空时作为乐观锁条件。
 */
public record RevokeLicenseRequest(
        @NotBlank String requestId,
        @NotBlank String licenseId,
        Integer expectedVersion) {
}
