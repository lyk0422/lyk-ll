package com.example.starter.pump.domain;

import java.math.BigDecimal;

/**
 * 合成泵站档案。
 *
 * @param code               泵站代码
 * @param name               泵站名称
 * @param zoneCode           所属流域分区代码
 * @param targetLevel        预降目标水位（米），恰达此值停泵
 * @param safeLowLevel       安全下限水位（米），严格低于立即停泵，恰等合法
 * @param gateStartOpening   启泵联动闸门开度（百分比）
 */
public record PumpStation(
        String code,
        String name,
        String zoneCode,
        BigDecimal targetLevel,
        BigDecimal safeLowLevel,
        BigDecimal gateStartOpening
) {
}
