package com.example.starter.fermentation.domain;

import java.time.LocalDateTime;

/**
 * 合成酵母单元。以唯一编号登记代次、状态与最近回收时刻。
 *
 * @param id                  单元 ID
 * @param unitCode            单元唯一编号
 * @param generation          当前代次：初始 0，每次合格回收加 1；代次 6 不允许启动新主酵
 * @param status              单元状态
 * @param lastRecoveredAt     最近一次合格回收时刻；首次单元为 null，不受 72 小时失效限制
 * @param occupiedByBatchId   当前占用该单元的主酵批次 ID；未被占用为 null
 * @param version             乐观锁版本号，从 1 开始，每次成功变更 +1
 * @param createdAt           单元登记时间
 */
public record YeastUnit(
        long id,
        String unitCode,
        int generation,
        YeastStatus status,
        LocalDateTime lastRecoveredAt,
        Long occupiedByBatchId,
        int version,
        LocalDateTime createdAt) {
}
