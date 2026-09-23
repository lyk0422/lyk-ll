package com.example.starter.repo;

import java.time.Instant;

import com.example.starter.domain.SpecimenStatus;

/**
 * 原始样本行模型。体积字段单位均为纳升（nL）。
 *
 * @param version 乐观锁版本，从 0 开始
 */
public record SpecimenRow(
        long id,
        String specimenId,
        long volumeNl,
        long remainingNl,
        SpecimenStatus status,
        String custodian,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
