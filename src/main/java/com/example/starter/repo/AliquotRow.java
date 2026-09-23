package com.example.starter.repo;

import java.time.Instant;

import com.example.starter.domain.AliquotStatus;

/**
 * 分装管行模型。体积字段单位均为纳升（nL）。
 *
 * @param version 乐观锁版本，从 0 开始
 */
public record AliquotRow(
        long id,
        String aliquotId,
        String specimenId,
        long volumeNl,
        long consumedNl,
        AliquotStatus status,
        String custodian,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 当前可占用体积 = 总体积 - 已消耗；预留体积通过 reservation 表实时汇总。
     */
    public long availableBeyondConsumed() {
        return volumeNl - consumedNl;
    }
}
