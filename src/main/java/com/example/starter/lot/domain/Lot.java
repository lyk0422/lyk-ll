package com.example.starter.lot.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 批次持久化对象。
 *
 * @param lotId       批次唯一标识
 * @param unit        计量单位，同一谱系转换必须一致
 * @param initialQty  初始/产出数量（三位小数，单位与 lotId 对应计量单位一致，必须为正）
 * @param consumedQty 累计已消费数量（只增；历史消费不可覆盖）
 * @param status      当前状态 AVAILABLE/QUARANTINED
 * @param version     乐观锁版本，每次该批次被转换消费后加一
 * @param createdAt   创建时间（Asia/Shanghai）
 * @param remark      备注，空值表示无备注
 */
public record Lot(
        String lotId,
        String unit,
        BigDecimal initialQty,
        BigDecimal consumedQty,
        LotStatus status,
        int version,
        LocalDateTime createdAt,
        String remark
) {
}
