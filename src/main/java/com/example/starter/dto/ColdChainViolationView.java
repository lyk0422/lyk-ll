package com.example.starter.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 冷链越界点视图：温度越出合成闭区间 [2,8] 的具体读数点（逐点列出，不按平均温度掩盖）。
 *
 * @param segmentSeq  所属片段序号
 * @param readingSeq  片内读数序号
 * @param at          采样 UTC 精确时刻
 * @param temperature 越界温度（摄氏度）
 * @param reason      越界原因说明
 */
public record ColdChainViolationView(
        int segmentSeq,
        int readingSeq,
        Instant at,
        BigDecimal temperature,
        String reason
) {
}
