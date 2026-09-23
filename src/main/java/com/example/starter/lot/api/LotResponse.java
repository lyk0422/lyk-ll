package com.example.starter.lot.api;

import com.example.starter.lot.domain.LotStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批次视图。
 *
 * @param lotId                   批次 ID
 * @param unit                    计量单位
 * @param initialQty              初始/产出数量
 * @param consumedQty             累计已消费数量
 * @param availableQty            可用余额 = 初始数量 - 累计消费
 * @param status                  当前状态
 * @param version                 乐观锁版本
 * @param activeQuarantineSources 当前仍生效的隔离来源批次 ID 集合（空表示无隔离）
 */
public record LotResponse(
        String lotId,
        String unit,
        BigDecimal initialQty,
        BigDecimal consumedQty,
        BigDecimal availableQty,
        LotStatus status,
        int version,
        List<String> activeQuarantineSources
) {
}
