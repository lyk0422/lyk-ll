package com.example.starter.slaughter.persistence;

import com.example.starter.slaughter.domain.BatchStatus;
import com.example.starter.slaughter.domain.PreQuarantineResult;

import java.time.Instant;

/**
 * 屠宰批次行对象。所有时刻均为 UTC。
 *
 * @param validUntilUtc 申报有效期截止（申报时刻 +6 小时），恰满即失效
 * @param inspectionChainId 当前同步检验链 ID；null 表示尚未开链（未屠宰）
 * @param checkedOutAtUtc 非 null 表示批次已到出厂核销终态
 * @param redeclareNo 重新申报次数
 */
public record BatchRow(
        Long id,
        String batchNo,
        BatchStatus status,
        int headCount,
        Instant declaredAtUtc,
        PreQuarantineResult preQuarantineResult,
        Instant preQuarantineAtUtc,
        Instant validUntilUtc,
        Long inspectionChainId,
        Instant judgedAtUtc,
        Instant checkedOutAtUtc,
        int redeclareNo,
        int version) {
}
