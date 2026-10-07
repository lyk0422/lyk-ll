package com.example.starter.fermentation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 源批实际清酒量台账。登记即冻结，等待唯一核销。
 *
 * @param id        台账 ID
 * @param batchId   受控源批 ID，一批一条
 * @param liters    实际清酒量（升，正数最多三位小数且不超过其发酵罐容量）
 * @param frozen    是否冻结，登记即为 true
 * @param version   台账版本号，从 1 起（冻结台账不允许修改）
 * @param createdAt 登记时间（UTC）
 */
public record SourceLiquor(
        long id,
        long batchId,
        BigDecimal liters,
        boolean frozen,
        int version,
        LocalDateTime createdAt) {
}
