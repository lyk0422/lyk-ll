package com.example.starter.cems.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 排口归档请求（归档为终态）。
 */
public record ArchiveRequest(@NotBlank String requestId, @NotBlank String outfallCode) {
}
