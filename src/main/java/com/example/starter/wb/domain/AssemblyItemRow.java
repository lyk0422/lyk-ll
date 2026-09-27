package com.example.starter.wb.domain;

import java.math.BigDecimal;

/**
 * 装配图明细行（assembly_item）：同图内集装器编号唯一、同舱位最多一器。
 * 重量单位 kg（皮重非负、净重为正、总重含皮重），力矩单位 kg·mm。
 */
public record AssemblyItemRow(
        Long id,
        long planId,
        String uldNumber,
        String uldType,
        String compartmentCode,
        int tareKg,
        int netKg,
        int totalKg,
        int armMm,
        BigDecimal momentKgMm,
        int seqNo
) {
}
