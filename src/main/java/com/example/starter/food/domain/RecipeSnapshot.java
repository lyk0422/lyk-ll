package com.example.starter.food.domain;

import java.util.List;

/**
 * 批准时冻结的配方版本快照；量产备案与后续复算均以此为准，不依赖可变的在途数据。
 */
public record RecipeSnapshot(
        String productCode,
        String recipeVersion,
        String categoryCode,
        String standardVersion,
        List<RecipeData.DirectAdditive> additives,
        List<RecipeData.Ingredient> ingredients) {

    public RecipeData toRecipeData() {
        return new RecipeData(categoryCode, additives, ingredients);
    }
}
