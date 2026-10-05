package com.example.starter.quarantine.model;

import com.example.starter.quarantine.ArrivalResult;
import com.example.starter.quarantine.CertificateStatus;
import com.example.starter.quarantine.DeclarationStatus;
import com.example.starter.quarantine.FlowPurpose;
import com.example.starter.quarantine.Species;
import com.example.starter.quarantine.TransitAlertStatus;
import com.example.starter.quarantine.TransportType;
import java.time.Instant;

/**
 * 数据库行模型。所有时间字段为 UTC。
 */
public final class Rows {

    private Rows() {
    }

    /** 疫点封锁区行。 */
    public record LockdownRow(
            long id,
            String requestId,
            String fingerprint,
            String farmId,
            String reason,
            String status,
            Instant createdAt) {
    }

    /** 检疫申报行。 */
    public record DeclarationRow(
            long id,
            String requestId,
            String fingerprint,
            String farmId,
            String destination,
            Species species,
            int quantity,
            String earTagRange,
            DeclarationStatus status,
            String rejectReason,
            TransportType transportType,
            Integer transitHours,
            String routeRegions,
            Instant createdAt) {
    }

    /** 检疫合格证行。 */
    public record CertificateRow(
            long id,
            String certNo,
            String issueRequestId,
            String fingerprint,
            String declarationRequestId,
            String farmId,
            String destination,
            Species species,
            int quantity,
            String earTagRange,
            CertificateStatus status,
            boolean quarantineObservation,
            int reportedQty,
            TransportType transportType,
            Integer transitHours,
            Instant transitDeadlineAt,
            String routeRegions,
            boolean enhancedMonitoring,
            Instant issuedAt,
            Instant expiresAt,
            Instant departedAt,
            Instant closedAt) {
    }

    /** 可售存栏行。 */
    public record StockRow(
            long id,
            String farmId,
            Species species,
            String earTagRange,
            int sellableQty) {
    }

    /** 落地报告行。 */
    public record ArrivalRow(
            long id,
            String requestId,
            String fingerprint,
            String certNo,
            int quantity,
            ArrivalResult result,
            String note,
            Instant createdAt) {
    }

    /** 流向登记行。 */
    public record FlowRegistrationRow(
            long id,
            String requestId,
            String fingerprint,
            String certNo,
            FlowPurpose purpose,
            Instant createdAt) {
    }

    /** 过境超时预警事件行。 */
    public record TransitAlertRow(
            long id,
            String alertKey,
            String certNo,
            TransitAlertStatus status,
            Instant triggeredAt,
            String resolveRequestId,
            String resolveFingerprint,
            String resolveNote,
            Instant resolvedAt,
            Instant createdAt) {
    }
}
