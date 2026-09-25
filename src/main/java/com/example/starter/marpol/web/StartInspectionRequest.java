package com.example.starter.marpol.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发起港口国核查请求（按船舶）。
 */
public record StartInspectionRequest(
        @NotBlank @Size(max = 32) String shipImo,
        @NotBlank @Size(max = 128) String officer) {
}
