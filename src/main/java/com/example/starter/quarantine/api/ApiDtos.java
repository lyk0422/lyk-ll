package com.example.starter.quarantine.api;

import com.example.starter.quarantine.AlertStatus;
import com.example.starter.quarantine.ArrivalResult;
import com.example.starter.quarantine.CertificateStatus;
import com.example.starter.quarantine.DeclarationStatus;
import com.example.starter.quarantine.Species;
import com.example.starter.quarantine.TransportType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;

/**
 * 检疫服务 API 请求/响应模型。所有时间字段为 UTC。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 检疫申报请求。transportType 缺省为 LOCAL；TRANSIT 必须给定 transitHours 过境时限。 */
    public record DeclarationCmd(
            @NotBlank String requestId,
            @NotBlank String farmId,
            @NotBlank String destination,
            @NotNull Species species,
            @Positive int quantity,
            @NotBlank String earTagRange,
            TransportType transportType,
            @Positive Integer transitHours) {

        /** 兼容旧本地调运申报（缺省 LOCAL，无过境时限）。 */
        public DeclarationCmd(String requestId, String farmId, String destination,
                              Species species, int quantity, String earTagRange) {
            this(requestId, farmId, destination, species, quantity, earTagRange, null, null);
        }
    }

    /** 检疫申报视图。 */
    public record DeclarationView(
            String requestId,
            String farmId,
            String destination,
            Species species,
            int quantity,
            String earTagRange,
            DeclarationStatus status,
            String rejectReason,
            TransportType transportType,
            Integer transitHours,
            Instant createdAt) {
    }

    /** 出证请求。 */
    public record IssueCmd(
            @NotBlank String requestId,
            @NotBlank String declarationRequestId) {
    }

    /** 检疫合格证视图。 */
    public record CertificateView(
            String certNo,
            String issueRequestId,
            String declarationRequestId,
            String farmId,
            String destination,
            Species species,
            int quantity,
            String earTagRange,
            CertificateStatus status,
            boolean quarantineObservation,
            TransportType transportType,
            Instant transitDeadline,
            boolean enhancedMonitoring,
            int reportedQty,
            Instant issuedAt,
            Instant expiresAt,
            Instant departedAt,
            Instant closedAt) {
    }

    /** 启运请求。 */
    public record DepartCmd(
            @NotBlank String requestId,
            @NotBlank String certNo) {
    }

    /** 疫点封锁发布请求。 */
    public record LockdownCmd(
            @NotBlank String requestId,
            @NotBlank String farmId,
            @NotBlank String reason) {
    }

    /** 疫点封锁发布结果，含三级联动处置统计。 */
    public record LockdownView(
            String requestId,
            String farmId,
            String reason,
            String status,
            Instant createdAt,
            int rejectedDeclarations,
            int voidedCertificates,
            int quarantinedInTransit) {
    }

    /** 落地报告请求。viaRegions 为本次行程途经区域清单（过境核销时用于疫区比对），可空。 */
    public record ArrivalCmd(
            @NotBlank String requestId,
            @NotBlank String certNo,
            @Positive int quantity,
            List<String> viaRegions) {

        /** 兼容旧落地报告（无途经区域）。 */
        public ArrivalCmd(String requestId, String certNo, int quantity) {
            this(requestId, certNo, quantity, null);
        }
    }

    /** 落地报告处理结果视图。 */
    public record ArrivalView(
            String requestId,
            String certNo,
            int quantity,
            ArrivalResult result,
            String note,
            CertificateStatus certStatus,
            boolean quarantineObservation,
            boolean enhancedMonitoring,
            int reportedQty,
            Instant createdAt) {
    }

    /** 可售存栏登记请求（合成数据入口）。certNo 可空：登记来源证号，用于按证书类型拦截不符用途的流向。 */
    public record StockCmd(
            @NotBlank String farmId,
            @NotNull Species species,
            @NotBlank String earTagRange,
            @Positive int quantity,
            String certNo) {

        /** 兼容旧存栏登记（不关联来源证号）。 */
        public StockCmd(String farmId, Species species, String earTagRange, int quantity) {
            this(farmId, species, earTagRange, quantity, null);
        }
    }

    /** 可售存栏视图。 */
    public record StockView(
            String farmId,
            Species species,
            String earTagRange,
            int sellableQty) {
    }

    /** 统一错误响应。 */
    public record ErrorView(String error, String message) {
    }

    /** 过境预警事件视图。 */
    public record AlertView(
            long id,
            String requestId,
            String certNo,
            String eventType,
            AlertStatus status,
            String note,
            String handleNote,
            Instant createdAt,
            Instant handledAt) {
    }

    /** 预警事件处理闭环请求（幂等）。 */
    public record AlertHandleCmd(
            @NotBlank String requestId,
            @NotNull Long alertId,
            String note) {
    }

    /** 过境超时扫描结果视图。 */
    public record AlertScanView(int generated) {
    }

    /** 证书轨迹视图：证书当前状态 + 落地报告留痕 + 预警事件留痕（审计口径）。 */
    public record TrajectoryView(
            CertificateView certificate,
            List<ArrivalView> arrivals,
            List<AlertView> alerts) {
    }
}
