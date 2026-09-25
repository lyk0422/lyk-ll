package com.example.starter.dye.web.dto;

import java.math.BigDecimal;

/**
 * 染化料视图。
 *
 * @param stockGrams 当前库存，单位克
 */
public record DyestuffResponse(
        String code,
        String name,
        BigDecimal stockGrams) {
}
