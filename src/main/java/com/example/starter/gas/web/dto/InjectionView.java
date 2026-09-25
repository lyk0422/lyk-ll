package com.example.starter.gas.web.dto;

import java.math.BigDecimal;

/**
 * 单次加注下发视图。
 *
 * @param doseGPerM3         投加比例，单位 g/m³
 * @param theoreticalConsumeG 理论消耗，单位 g
 * @param actualConsumeG      实际下发消耗，单位 g；锁阀期间为 0
 * @param valveLocked         下发时阀门是否锁定
 */
public record InjectionView(
        BigDecimal doseGPerM3,
        BigDecimal theoreticalConsumeG,
        BigDecimal actualConsumeG,
        boolean valveLocked
) {
}
