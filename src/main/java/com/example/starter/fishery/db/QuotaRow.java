package com.example.starter.fishery.db;

import java.math.BigDecimal;

/**
 * 配额台账行：船＋鱼种＋UTC 年度核定，{@code quotaKg} 为年度配额（千克，不含结转），
 * {@code usedKg} 为已核销扣减数量（千克，不含结转核销），
 * {@code alertTriggered} 为产地预警标志（TRUE 时该额度不得结转），{@code version} 为乐观锁版本号。
 */
public record QuotaRow(
        String vesselCode,
        String speciesCode,
        int year,
        BigDecimal quotaKg,
        BigDecimal usedKg,
        boolean alertTriggered,
        int version) {
}
