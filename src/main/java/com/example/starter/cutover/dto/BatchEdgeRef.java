package com.example.starter.cutover.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 批次前置依赖边引用：predecessor -> successor，两端必须都在批内。
 */
public record BatchEdgeRef(
        @NotBlank String predecessor,
        @NotBlank String successor
) {
}
