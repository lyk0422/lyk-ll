package com.example.starter.blast.api;

import java.time.Instant;

/**
 * 矿区状态视图。
 *
 * @param frozen           是否冻结
 * @param frozenAt         最近冻结生效时刻（UTC），NULL 从未冻结
 * @param frozenReason     冻结原因
 * @param unfrozenAt       最近解冻时刻（UTC）
 * @param unfreezeReviewer 解冻复核人
 */
public record MineResponse(
        String mineCode,
        String mineName,
        String timeZone,
        boolean frozen,
        Instant frozenAt,
        String frozenReason,
        Instant unfrozenAt,
        String unfreezeReviewer) {
}
