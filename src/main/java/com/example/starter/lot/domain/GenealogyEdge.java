package com.example.starter.lot.domain;

import java.math.BigDecimal;

/**
 * 谱系边（父 -> 子）。
 *
 * @param parentLotId  父批次 ID
 * @param childLotId   子批次 ID
 * @param conversionId 产生该边的转换单
 * @param qty          该父子关系上的投入量
 */
public record GenealogyEdge(
        String parentLotId,
        String childLotId,
        long conversionId,
        BigDecimal qty
) {
}
