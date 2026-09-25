package com.example.starter.quarantine.api;

import com.example.starter.quarantine.ArrivalResult;
import com.example.starter.quarantine.CertificateStatus;
import com.example.starter.quarantine.DeclarationStatus;
import com.example.starter.quarantine.Species;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * 检疫服务 API 请求/响应模型。所有时间字段为 UTC。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 检疫申报请求。 */
    public record DeclarationCmd(
            @NotBlank String requestId,
            @NotBlank String farmId,
            @NotBlank String destination,
            @NotNull Species species,
            @Positive int quantity,
            @NotBlank String earTagRange) {
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

    /** 统一错误响应。 */
    public record ErrorView(String error, String message) {
    }
}
