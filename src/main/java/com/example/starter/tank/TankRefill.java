package com.example.starter.tank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 补液单：唯一运输批号，两名不同交接人共同确认后原子入账。
 *
 * @param id                 补液单ID
 * @param tankId             储罐ID
 * @param transportBatchNo   唯一运输批号
 * @param quantityKg         补液数量 kg
 * @param concentrationMgM3  合成验收浓度 mg/m3，须在许可闭区间内
 * @param firstOperator      第一名交接人工号
 * @param secondOperator     第二名交接人工号，须与第一人不同
 * @param receivedAt         验收时刻（业务时间）
 */
public record TankRefill(Long id, Long tankId, String transportBatchNo, BigDecimal quantityKg,
                         BigDecimal concentrationMgM3, String firstOperator, String secondOperator,
                         LocalDateTime receivedAt) {
}
