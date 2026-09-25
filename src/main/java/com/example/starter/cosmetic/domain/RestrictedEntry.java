package com.example.starter.cosmetic.domain;

import java.math.BigDecimal;

/**
 * 限用成分条目，maxConcentration 为浓度限值（质量分数%），恰等限值合法。
 */
public record RestrictedEntry(String code, String name, BigDecimal maxConcentration) {
}
