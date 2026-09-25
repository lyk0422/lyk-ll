package com.example.starter.compliance.domain;

import java.math.BigDecimal;

/**
 * 配方中直接添加的添加剂。
 *
 * @param additiveCode 添加剂目录编码
 * @param amount       添加量，单位 g/kg（每千克终产品中克数）
 */
public record DirectAdditive(String additiveCode, BigDecimal amount) {
}
