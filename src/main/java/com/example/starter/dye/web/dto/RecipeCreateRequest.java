package com.example.starter.dye.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 配方新建请求（生成草稿版本 V1）。
 *
 * @param liquorRatio 浴比 1:N 中的 N
 */
public record RecipeCreateRequest(
        @NotBlank String recipeCode,
        @NotBlank String name,
        @Min(1) int liquorRatio,
        @NotEmpty @Valid List<RecipeItemRequest> items) {
}
