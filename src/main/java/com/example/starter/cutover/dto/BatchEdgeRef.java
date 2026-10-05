package com.example.starter.cutover.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 批次内有向前置依赖边。两端都必须是批内成员，不允许自环与环路。
 */
public record BatchEdgeRef(
        @NotBlank String predecessor,
        @NotBlank String successor
) {
}
