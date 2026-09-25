package com.example.starter.rt.dto;

import java.math.BigDecimal;

/**
 * 模体验证结果。偏差 &gt; 3% 时 passed=false 且计划不进入 VERIFIED；恰 3% 合法。
 *
 * @param planVersionId    计划版本ID
 * @param versionNo        版本号
 * @param status           验证后状态（通过为 VERIFIED，失败保持 APPROVED）
 * @param requestId        幂等键
 * @param replayed         是否为同键同参重放
 * @param plannedDoseCgy   模体计划参考剂量(cGy)
 * @param measuredDoseCgy  模体实测剂量(cGy)
 * @param deviationPct     偏差百分比 = |实测-计划|/计划×100
 * @param passed           是否 ≤3%
 */
public record VerificationResponse(
        Long planVersionId,
        Integer versionNo,
        String status,
        String requestId,
        boolean replayed,
        Long plannedDoseCgy,
        Long measuredDoseCgy,
        BigDecimal deviationPct,
        boolean passed) {
}
