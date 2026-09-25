package com.example.starter.blast.domain;

import java.time.Instant;

/**
 * 矿区持久化对象。
 *
 * @param frozen            是否处于冻结状态
 * @param frozenAt          最近一次冻结生效时刻（UTC），NULL 从未冻结
 * @param frozenReason      冻结原因
 * @param unfrozenAt        最近一次监管复核解冻时刻（UTC）
 * @param unfreezeReviewer  解冻复核人
 * @param version           乐观锁版本
 */
public record Mine(
        String mineCode,
        String mineName,
        String timeZone,
        boolean frozen,
        Instant frozenAt,
        String frozenReason,
        Instant unfrozenAt,
        String unfreezeReviewer,
        long version) {
}
