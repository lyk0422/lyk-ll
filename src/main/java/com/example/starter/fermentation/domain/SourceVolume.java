package com.example.starter.fermentation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 源批实际清酒升数台账。登记一次后冻结，启用一次核销管控
 * （旧单笔转出与合批接收共享唯一核销，先到者消费）。
 *
 * @param id           台账记录 ID
 * @param batchId      源批次 ID，每批仅登记一次
 * @param volumeLiters 实际清酒升数，单位升，正数最多三位小数且不超过源发酵罐容量
 * @param version      台账版本号，从 1 开始；登记后冻结不再变更
 * @param createdAt    登记时间（UTC）
 */
public record SourceVolume(
        long id,
        long batchId,
        BigDecimal volumeLiters,
        int version,
        LocalDateTime createdAt) {
}
