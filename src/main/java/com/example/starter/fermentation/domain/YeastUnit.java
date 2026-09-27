package com.example.starter.fermentation.domain;

import java.time.LocalDateTime;

/**
 * 合成酵母单元。时间字段为业务发生时间（本地时间，无时区）。
 *
 * @param id               酵母单元 ID
 * @param yeastCode        酵母单元唯一编号
 * @param generation       当前代次，初始 0；回收合格时加 1；代次 6 不可再投酵
 * @param status           单元状态
 * @param version          乐观锁版本，从 1 开始，每次成功变更加 1
 * @param lastRecoveredAt  最近一次回收时刻，首次登记为 null（不受 72 小时失效限制）
 * @param activeBatchId    当前投酵绑定的批次 ID，未绑定为 null
 */
public record YeastUnit(
        long id,
        String yeastCode,
        int generation,
        YeastUnitStatus status,
        int version,
        LocalDateTime lastRecoveredAt,
        Long activeBatchId) {
}
