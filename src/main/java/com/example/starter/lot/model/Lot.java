package com.example.starter.lot.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 批次持久化对象。
 *
 * @param lotId        批次唯一编号
 * @param unit         计量单位；一次转换内全部父子批次必须一致
 * @param initialQty   建档初始数量（三位小数，为正）
 * @param producedQty  累计产出数量（建档批次等于初始数量）
 * @param consumedQty  累计被消费数量
 * @param status       当前状态
 * @param version      乐观版本号，仅在数量台账变动（被下游消费）时递增；隔离状态变化不递增
 * @param createdAt    建档时间（Asia/Shanghai）
 */
public record Lot(
        String lotId,
        String unit,
        BigDecimal initialQty,
        BigDecimal producedQty,
        BigDecimal consumedQty,
        LotStatus status,
        long version,
        LocalDateTime createdAt
) {
    /** 当前可用余额 = 累计产出 - 累计消费，三位小数。 */
    public BigDecimal availableQty() {
        return producedQty.subtract(consumedQty);
    }
}
