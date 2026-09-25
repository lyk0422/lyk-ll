package com.example.starter.compliance.domain;

import java.util.List;

/**
 * 配方完整业务内容（提交入参与存储模型共用）。
 *
 * @param productCode 产品编码
 * @param productName 产品名称
 * @param categoryCode 食品分类编码
 * @param version    配方版本号（同一产品内唯一）
 * @param additives  直接添加的添加剂列表
 * @param ingredients 复合配料使用列表
 */
public record FormulaContent(
        String productCode,
        String productName,
        String categoryCode,
        Integer version,
        List<DirectAdditive> additives,
        List<IngredientUse> ingredients) {
}
