package com.example.starter.flood.web.dto;

import java.math.BigDecimal;

/**
 * 单版本复算结果视图：仅依据该版本留存的源输入快照重算，与存库结果比对。
 */
public record RecomputeView(
        String rollingNo,
        Integer versionNo,
        boolean consistent,
        BigDecimal storedStorage,
        BigDecimal recomputedStorage,
        String storedReleaseJudgment,
        String recomputedReleaseJudgment,
        String storedDangerJudgment,
        String recomputedDangerJudgment,
        String criterion) {
}
