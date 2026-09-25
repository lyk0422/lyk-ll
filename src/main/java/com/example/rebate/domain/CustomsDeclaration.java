package com.example.rebate.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 报关单（合成数据）。
 *
 * @param fobAmount FOB 价，计税依据输入
 * @param fobUsd FOB 美元价，换汇成本分母
 */
public record CustomsDeclaration(
        Long id,
        String declarationNo,
        String productCode,
        BigDecimal fobAmount,
        BigDecimal fobUsd,
        Instant collectedAt) {
}
