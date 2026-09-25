package com.example.starter.quarantine.api.dto;

import com.example.starter.quarantine.domain.CertificateStatus;
import java.time.Instant;

/**
 * 检疫合格证视图。
 *
 * @param certificateNo  证号（出证时生成）
 * @param issuedAt       出证时刻，UTC
 * @param expiresAt      有效期终点，UTC
 * @param status         证状态 VALID/VOID/EXPIRED/CLOSED
 * @param quarantineFlag 强制隔离观察标记
 */
public record CertificateView(
        String certificateNo,
        String requestId,
        String farmId,
        String animalType,
        String earTagRange,
        int quantity,
        Instant issuedAt,
        Instant expiresAt,
        CertificateStatus status,
        boolean quarantineFlag) {
}
