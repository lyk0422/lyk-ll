package com.example.starter.marpol.model;

import com.example.starter.marpol.domain.RecordType;
import com.example.starter.marpol.domain.RecordVersionStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 排放事件记录的一个版本行。
 *
 * <p>修正不物理删除：新版本行引用 {@code supersedesId}，原版本保留；
 * 同版本族由 {@code rootId} 关联，{@code versionNo} 在族内唯一。</p>
 */
public record EmissionRecord(
        Long id,
        String requestId,
        Long rootId,
        Integer versionNo,
        Long supersedesId,
        Long shipId,
        Long voyageId,
        RecordType recordType,
        double longitude,
        double latitude,
        Instant eventTime,
        BigDecimal quantity,
        String unit,
        RecordVersionStatus status,
        String fingerprint,
        Instant createdAt) {
}
