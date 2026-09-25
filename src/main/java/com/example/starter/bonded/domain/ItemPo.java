package com.example.starter.bonded.domain;

import java.math.BigDecimal;

/**
 * 转关单商品项持久化对象。
 *
 * @param id 主键
 * @param orderId 转关单主键
 * @param hsCode 商品税号
 * @param quantity 核注数量（件）
 * @param unitPrice 单价（元）
 * @param verifiedQty 累计已核销数量（件）
 */
public record ItemPo(
        Long id,
        Long orderId,
        String hsCode,
        Long quantity,
        BigDecimal unitPrice,
        Long verifiedQty) {
}
