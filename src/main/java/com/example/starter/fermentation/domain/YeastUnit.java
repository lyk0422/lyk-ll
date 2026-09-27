package com.example.starter.fermentation.domain;

import java.time.LocalDateTime;

/**
 * 合成酵母单元：以唯一编号登记代次、状态与最近回收时刻。
 *
 * @param unitCode          酵母单元唯一编号
 * @param generation        当前代次：登记初始为 0，每次合格回收后加 1；代次 6 不可启动新主酵
 * @param status            单元状态：AVAILABLE 可用 / IN_USE 使用中 / SCRAPPED 已报废
 * @param lastRecoveredAt   最近一次合格回收时刻；首次单元从未回收为 null（不受 72 小时时限约束）
 * @param occupiedByBatchId 当前占用该单元的主酵批次 ID；未被占用为 null
 * @param version           乐观版本号，从 1 开始，每次成功变更加 1
 */
public record YeastUnit(
        String unitCode,
        int generation,
        YeastStatus status,
        LocalDateTime lastRecoveredAt,
        Long occupiedByBatchId,
        int version) {
}
