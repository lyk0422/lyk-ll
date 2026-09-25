package com.example.starter.damage.repo.po;

import java.math.BigDecimal;

/** iicl_code 关联 repair_item 的解析结果。 */
public record IiclCodePo(
        String code,
        String description,
        String repairCode,
        String repairName,
        String unit,
        BigDecimal unitCost) {
}
