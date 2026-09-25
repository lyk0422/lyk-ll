package com.example.starter.compliance.engine;

import com.example.starter.compliance.domain.DirectAdditive;
import com.example.starter.compliance.domain.IngredientUse;
import com.example.starter.compliance.repo.AdditiveRow;
import com.example.starter.compliance.repo.IngredientAdditiveRow;
import com.example.starter.compliance.repo.LimitRow;
import java.util.List;
import java.util.Map;

/**
 * 一次合规审查的全部输入数据（由服务层从仓储组装，引擎本身不访问数据库，可独立复算）。
 *
 * @param categoryCode        配方食品分类
 * @param directAdditives     配方直接添加的添加剂及用量（g/kg）
 * @param ingredientUses      复合配料及占终产品质量比
 * @param additiveCatalog     添加剂目录：编码 → 目录行（含功能类别）
 * @param knownIngredients    已知复合配料编码集合
 * @param ingredientAdditives 配料编码 → 该配料中各添加剂本底含量（g/kg 配料）
 * @param limits              该食品分类下添加剂编码 → 限量行（g/kg 终产品）
 */
public record ReviewInput(
        String categoryCode,
        List<DirectAdditive> directAdditives,
        List<IngredientUse> ingredientUses,
        Map<String, AdditiveRow> additiveCatalog,
        java.util.Set<String> knownIngredients,
        Map<String, List<IngredientAdditiveRow>> ingredientAdditives,
        Map<String, LimitRow> limits) {
}
