package com.example.starter.dye.web.dto;

import java.math.BigDecimal;

/**
 * 染缸视图；activeRunNo 非空表示该缸当前有执行中缸次。
 */
public record VatResponse(
        Long id,
        String vatCode,
        BigDecimal capacityKg,
        String activeRunNo) {
}
