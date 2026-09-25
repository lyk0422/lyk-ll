package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 室内检测记录行（发芽率）。
 *
 * @param id              主键
 * @param lotId           批次主键
 * @param round           检验轮次：1 首检，2 复检
 * @param germinationRate 发芽率，单位：%（百分比）
 * @param passOriginal    是否达到原种门槛 90%
 * @param passBreeder     是否达到良种门槛 85%
 * @param retestable      不合格可复检标记
 * @param inspector       室内检测员（合成数据）
 * @param inspectedAt     室内检测时间（UTC）
 * @param createdAt       入库时间（UTC）
 */
public record LabInspection(
        Long id,
        long lotId,
        int round,
        BigDecimal germinationRate,
        boolean passOriginal,
        boolean passBreeder,
        boolean retestable,
        String inspector,
        Instant inspectedAt,
        Instant createdAt) {
}
