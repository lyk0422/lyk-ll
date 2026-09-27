package com.example.starter.tank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 补液单：唯一运输批号 + 双交接人 + 合成验收浓度，原子入账。
 *
 * @param id                        主键
 * @param stationId                 门站ID
 * @param tankId                    储罐ID
 * @param requestId                 幂等键
 * @param transportBatchNo          唯一运输批号
 * @param quantityKg                补液数量 kg 三位小数
 * @param acceptedConcentrationMgM3 合成验收浓度 mg/m3，须在许可闭区间
 * @param firstHandler              第一名交接人
 * @param secondHandler             第二名交接人（须与第一人不同）
 * @param balanceBeforeKg           入账前余额 kg
 * @param balanceAfterKg            入账后余额 kg
 * @param bizTime                   交接时刻
 */
public record ReplenishmentOrder(
        Long id,
        Long stationId,
        Long tankId,
        String requestId,
        String transportBatchNo,
        BigDecimal quantityKg,
        BigDecimal acceptedConcentrationMgM3,
        String firstHandler,
        String secondHandler,
        BigDecimal balanceBeforeKg,
        BigDecimal balanceAfterKg,
        LocalDateTime bizTime) {
}
