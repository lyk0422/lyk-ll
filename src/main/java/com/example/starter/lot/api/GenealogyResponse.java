package com.example.starter.lot.api;

import java.util.List;

/**
 * 谱系上下游视图。
 *
 * @param lotId    查询批次
 * @param parents  直接父批次
 * @param children 直接子批次
 * @param ancestors   全部祖先（去重，向上游方向）
 * @param descendants 全部后代（去重，向下游方向）
 */
public record GenealogyResponse(
        String lotId,
        List<Relative> parents,
        List<Relative> children,
        List<String> ancestors,
        List<String> descendants
) {

    /**
     * 直接相邻批次。
     *
     * @param lotId        相邻批次 ID
     * @param conversionId 关联转换单 ID
     * @param qty          该边上的数量
     */
    public record Relative(String lotId, long conversionId, java.math.BigDecimal qty) {
    }
}
