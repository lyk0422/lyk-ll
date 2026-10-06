package com.example.starter.pesticide;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 药剂库存数据行。
 *
 * @param id 主键
 * @param name 药剂名称
 * @param quantity 当前库存量
 * @param unit 计量单位
 * @param safetyLine 安全库存线
 * @param expiryDate 有效期截止日（含当日），过期拒绝登记投放
 */
public record PesticideRow(long id, String name, BigDecimal quantity, String unit,
        BigDecimal safetyLine, LocalDate expiryDate) {
}
