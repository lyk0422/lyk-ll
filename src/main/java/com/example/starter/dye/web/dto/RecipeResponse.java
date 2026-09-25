package com.example.starter.dye.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 配方版本视图。
 */
public record RecipeResponse(
        Long id,
        String recipeCode,
        Integer version,
        String name,
        Integer liquorRatio,
        String status,
        LocalDateTime publishedAt,
        List<RecipeItemResponse> items) {
}
