package com.example.starter.fermentation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合成清酒接收罐台账。
 *
 * @param id             接收罐 ID
 * @param tankCode       接收罐编号，全局唯一
 * @param capacityLiters 容量，单位升，正数最多三位小数；不得缩到已接收量以下
 * @param receivedLiters 已接收清酒总量，单位升，等于各合批接收单合计
 * @param version        台账版本号，从 1 开始，每次成功变更（调容或接收）加 1
 * @param createdAt      登记时间（UTC）
 */
public record ReceivingTank(
        long id,
        String tankCode,
        BigDecimal capacityLiters,
        BigDecimal receivedLiters,
        int version,
        LocalDateTime createdAt) {
}
