package com.example.starter.dye.web.dto;

import java.math.BigDecimal;

/**
 * 单项称量核验结果。
 */
public record WeighResult(
        String dyestuffCode,
        BigDecimal plannedGrams,
        BigDecimal actualGrams,
        BigDecimal deviationRatio,
        boolean accepted) {
}
