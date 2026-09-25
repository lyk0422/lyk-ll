package com.example.starter.inspection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.example.starter.domain.TestVerdict;

/**
 * 快检记录，按（批次＋项目）唯一去重。抑制率单位 %；时间均为 UTC。
 */
public record RapidTest(
        long id,
        String requestId,
        String fingerprint,
        long batchId,
        String item,
        BigDecimal inhibitionRate,
        TestVerdict verdict,
        LocalDateTime createdAt) {
}
