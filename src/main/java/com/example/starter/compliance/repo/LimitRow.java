package com.example.starter.compliance.repo;

import java.math.BigDecimal;

/**
 * 添加剂限量标准行；maxAmount 单位 g/kg 终产品，恰等限量合法。
 */
public record LimitRow(String additiveCode, String categoryCode, BigDecimal maxAmount, long updatedAt) {
}
