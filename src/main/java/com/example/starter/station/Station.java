package com.example.starter.station;

import java.math.BigDecimal;

/**
 * 门站配置。
 *
 * @param id                     主键
 * @param stationCode            门站编码
 * @param name                   门站名称
 * @param pressureLowLimitMpa    压力下限 MPa，恰等合格
 * @param pressureHighLimitMpa   压力上限 MPa，恰等合格
 * @param pressureTargetMpa      调压目标压力 MPa
 * @param targetConcentrationMgM3 加臭目标浓度 mg/m3
 * @param concentrationTolerancePct 浓度允许偏差百分比（20 表示 ±20%）
 */
public record Station(
        Long id,
        String stationCode,
        String name,
        BigDecimal pressureLowLimitMpa,
        BigDecimal pressureHighLimitMpa,
        BigDecimal pressureTargetMpa,
        BigDecimal targetConcentrationMgM3,
        BigDecimal concentrationTolerancePct) {
}
