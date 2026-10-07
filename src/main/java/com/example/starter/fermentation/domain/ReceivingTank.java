package com.example.starter.fermentation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合成清酒接收罐。
 *
 * @param id             接收罐 ID
 * @param tankCode       接收罐编号，全局唯一
 * @param capacityLiters 容量（升，正数最多三位小数），不得缩到已接收量以下
 * @param receivedLiters 累计已接收清酒量（升），仅由合批受理事务原子累加
 * @param version        乐观版本号，从 1 起，成功变更加 1
 * @param createdAt      登记时间（UTC）
 * @param updatedAt      最近变更时间（UTC）
 */
public record ReceivingTank(
        long id,
        String tankCode,
        BigDecimal capacityLiters,
        BigDecimal receivedLiters,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
