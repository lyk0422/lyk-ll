package com.example.starter.quarantine.api;

import com.example.starter.quarantine.ArrivalResult;
import com.example.starter.quarantine.CertificateStatus;
import com.example.starter.quarantine.DeclarationStatus;
import com.example.starter.quarantine.FlowPurpose;
import com.example.starter.quarantine.Species;
import com.example.starter.quarantine.TransitAlertStatus;
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

    /** 检疫申报请求。transportType 缺省为 LOCAL；TRANSIT 须给定时限（小时）与途经区域清单。 */
    public record DeclarationCmd(
            @NotBlank String requestId,
            @NotBlank String farmId,
            @NotBlank String destination,
            @NotNull Species species,
            @Positive int quantity,
            @NotBlank String earTagRange,
            TransportType transportType,
            @Positive Integer transitHours,
            List<@NotBlank String> routeRegions) {

        /** 兼容本地调运的简式构造：运输类型 LOCAL，无过境字段。 */
        public DeclarationCmd(String requestId, String farmId, String destination,
                              Species species, int quantity, String earTagRange) {
            this(requestId, farmId, destination, species, quantity, earTagRange, null, null, null);
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
            List<String> routeRegions,
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
            int reportedQty,
            TransportType transportType,
            Instant transitDeadlineAt,
            boolean enhancedMonitoring,
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

    /** 落地报告请求。 */
    public record ArrivalCmd(
            @NotBlank String requestId,
            @NotBlank String certNo,
            @Positive int quantity) {
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
            int reportedQty,
            boolean enhancedMonitoring,
            Instant createdAt) {
    }

    /** 可售存栏登记请求（合成数据入口）。 */
    public record StockCmd(
            @NotBlank String farmId,
            @NotNull Species species,
            @NotBlank String earTagRange,
            @Positive int quantity) {
    }

    /** 可售存栏视图。 */
    public record StockView(
            String farmId,
            Species species,
            String earTagRange,
            int sellableQty) {
    }

    /** 流向登记请求（幂等）。 */
    public record FlowCmd(
            @NotBlank String requestId,
            @NotBlank String certNo,
            @NotNull FlowPurpose purpose) {
    }

    /** 流向登记视图。 */
    public record FlowView(
            String requestId,
            String certNo,
            FlowPurpose purpose,
            Instant createdAt) {
    }

    /** 过境预警办结请求（幂等）。 */
    public record ResolveAlertCmd(
            @NotBlank String requestId,
            String note) {
    }

    /** 过境超时预警事件视图。 */
    public record TransitAlertView(
            String alertKey,
            String certNo,
            TransitAlertStatus status,
            Instant triggeredAt,
            Instant resolvedAt,
            String resolveNote) {
    }

    /** 证书轨迹事件（审计口径：出证、启运、落地、流向、预警、结案）。 */
    public record TrajectoryEvent(
            String type,
            String detail,
            Instant at) {
    }

    /** 证书轨迹视图。 */
    public record TrajectoryView(
            String certNo,
            TransportType transportType,
            List<TrajectoryEvent> events) {
    }

    /** 统一错误响应。 */
    public record ErrorView(String error, String message) {
    }
}
