package com.example.starter.food.domain;

import java.math.BigDecimal;

/**
 * 单个添加剂在终产品中的有效用量（直接加入 + 复合配料带入折算）。
 */
public record EffectiveAdditive(
        String additiveCode,
        /** 直接加入量 g/kg */
        BigDecimal directAmount,
        /** 复合配料带入折算量 g/kg */
        BigDecimal carryOverAmount,
        /** 终产品有效含量 g/kg */
        BigDecimal effectiveAmount) {
}
