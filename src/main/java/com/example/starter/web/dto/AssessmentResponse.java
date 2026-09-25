package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 逐日偏差考核明细，可逐日复算。
 *
 * @param stationCode 交接站编码
 * @param gasDay 气量日（UTC）
 * @param voucherNo 考核依据凭证号
 * @param actualEnergyMj 实际交接能量，单位 MJ
 * @param nominatedEnergyMj 日指定能量，单位 MJ
 * @param deviationMj 偏差能量（实际-指定），单位 MJ
 * @param deviationAbsMj 偏差绝对值，单位 MJ
 * @param deviationRatio 偏差绝对值/日指定
 * @param exempt 是否在 3%（含）免考区间
 * @param billableExcessMj 超出免考区间的计费偏差能量，单位 MJ
 * @param penaltyUnitPrice 违约金单价，单位 元/MJ
 * @param penaltyAmount 违约金金额，单位 元，保留两位小数
 */
public record AssessmentResponse(
        String stationCode,
        LocalDate gasDay,
        String voucherNo,
        BigDecimal actualEnergyMj,
        BigDecimal nominatedEnergyMj,
        BigDecimal deviationMj,
        BigDecimal deviationAbsMj,
        BigDecimal deviationRatio,
        boolean exempt,
        BigDecimal billableExcessMj,
        BigDecimal penaltyUnitPrice,
        BigDecimal penaltyAmount) {
}
