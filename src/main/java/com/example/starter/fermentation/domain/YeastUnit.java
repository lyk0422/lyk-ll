package com.example.starter.fermentation.domain;

import java.time.LocalDateTime;

/**
 * 合成酵母单元。时间字段为业务发生时间（本地时间，无时区），未发生时为 null。
 *
 * @param id              酵母单元 ID
 * @param unitCode        酵母单元编号，全局唯一
 * @param generation      当前代次，初始 0；回收合格（活率≥90% 且无污染）后加 1；代次 6 不可再投酵
 * @param status          单元状态
 * @param lastRecoveredAt 最近一次回收时刻，首次单元（未回收过）为 null，不受 72 小时失效限制
 * @param version         乐观锁版本，从 1 开始，每次成功变更加 1
 * @param activeBatchId   当前占用的主酵批次 ID，未占用为 null
 */
public record YeastUnit(
        long id,
        String unitCode,
        int generation,
        YeastUnitStatus status,
        LocalDateTime lastRecoveredAt,
        int version,
        Long activeBatchId) {
}
