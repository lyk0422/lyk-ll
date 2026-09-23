package com.example.starter.repo;

import java.time.Instant;

import com.example.starter.domain.ReservationStatus;

/**
 * 检测申请（占用记录）行模型。体积字段单位为纳升（nL）。
 *
 * @param version 乐观锁版本，从 0 开始
 */
public record ReservationRow(
        long id,
        String requestId,
        String aliquotId,
        String projectCode,
        long volumeNl,
        ReservationStatus status,
        String custodian,
        int version,
        String resultPayload,
        Instant resultAt,
        Instant createdAt,
        Instant updatedAt) {
}
