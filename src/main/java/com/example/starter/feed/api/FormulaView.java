package com.example.starter.feed.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 配方版本视图，提交/批准接口均返回该结构。
 *
 * @param formulaCode   配方编码
 * @param versionNo     版本号（同配方内自增）
 * @param status        SUBMITTED / REJECTED / APPROVED
 * @param targetSpecies 目标动物种类；非含药配方可为空
 * @param medicated     是否含药物添加剂
 * @param items         版本冻结时的组成项快照
 * @param rejectReasons 合规拒绝原因；通过时为空列表
 */
public record FormulaView(
        String formulaCode,
        int versionNo,
        String status,
        String targetSpecies,
        boolean medicated,
        List<Item> items,
        List<String> rejectReasons) {

    /**
     * 组成项视图。
     */
    public record Item(String ingredientCode, String ingredientName, BigDecimal ratio) {
    }
}
