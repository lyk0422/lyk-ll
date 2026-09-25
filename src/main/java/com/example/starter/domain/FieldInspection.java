package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 田间检验记录行（纯度）。
 *
 * @param id           主键
 * @param lotId        批次主键
 * @param round        检验轮次：1 首检，2 复检
 * @param purity       品种纯度，单位：%（百分比）
 * @param passOriginal 是否达到原种门槛 99.9%
 * @param passBreeder  是否达到良种门槛 99.0%
 * @param retestable   不合格可复检标记
 * @param inspector    田间检验员（合成数据）
 * @param inspectedAt  田间检验时间（UTC）
 * @param createdAt    入库时间（UTC）
 */
public record FieldInspection(
        Long id,
        long lotId,
        int round,
        BigDecimal purity,
        boolean passOriginal,
        boolean passBreeder,
        boolean retestable,
        String inspector,
        Instant inspectedAt,
        Instant createdAt) {
}
