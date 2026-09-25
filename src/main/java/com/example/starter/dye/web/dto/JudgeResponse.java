package com.example.starter.dye.web.dto;

import java.math.BigDecimal;

/**
 * 缸差判定结果。
 *
 * @param deltaEGap 两缸出缸 ΔE 差值绝对值；无对照缸时为本缸出缸 ΔE（对标准样）
 * @param repairCount 判定后累计回修次数
 */
public record JudgeResponse(
        String requestId,
        String runNo,
        String baselineRunNo,
        BigDecimal deltaEGap,
        String disposition,
        int repairCount,
        String status) {
}
