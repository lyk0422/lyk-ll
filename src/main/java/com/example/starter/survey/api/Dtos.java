package com.example.starter.survey.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 船级检验接口请求/响应 DTO 集合。日期字段均为 UTC 日历日（ISO yyyy-MM-dd）。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 申请检验：同船同类型同时刻仅允许一个进行中检验。 */
    public record ApplySurveyRequest(
            @NotBlank String requestId,
            @NotBlank String imo,
            @NotBlank String typeCode) {
    }

    /** 登轮检验：可同时登记发现的缺陷；boardDate 为登轮完成日（UTC）。 */
    public record BoardSurveyRequest(
            @NotBlank String requestId,
            @NotNull Long surveyId,
            @NotNull LocalDate boardDate,
            @Size(max = 50) List<DefectInput> defects) {
    }

    /** 缺陷登记项（合成描述）。 */
    public record DefectInput(
            @NotBlank String severity,
            @NotBlank @Size(max = 256) String description) {
    }

    /** 缺陷整改上报。重大缺陷整改即受理；一般缺陷进入待复查。 */
    public record RectifyDefectRequest(
            @NotBlank String requestId,
            @NotNull Long defectId,
            @NotNull LocalDate rectifiedDate) {
    }

    /** 一般缺陷复查提交：恰第 30 日提交受理，逾期证书失效。 */
    public record RecheckDefectRequest(
            @NotBlank String requestId,
            @NotNull Long defectId,
            @NotNull LocalDate submitDate) {
    }

    /** 发证：签发为终态，重复签发幂等返回。 */
    public record IssueCertificateRequest(
            @NotBlank String requestId,
            @NotNull Long surveyId,
            @NotNull LocalDate issueDate) {
    }

    /** 展期申请：仅年度检验可申请，一次最长 3 个月（恰 3 个月合法）。 */
    public record ApplyExtensionRequest(
            @NotBlank String requestId,
            @NotBlank String imo,
            @NotBlank String typeCode,
            @NotNull @Positive Integer months) {
    }

    /** 展期批准：批准与原证书有效期变更在同一事务内完成。 */
    public record ApproveExtensionRequest(
            @NotBlank String requestId,
            @NotNull Long extensionId) {
    }

    /** 发证后迟到的检验记录：仅登记迟到事件，不改终态。 */
    public record LateRecordRequest(
            @NotBlank String requestId,
            @NotNull Long surveyId,
            @NotNull LocalDate recordDate,
            @Size(max = 256) String detail) {
    }

    /** 复算请求：窗口台账/缺陷链/证书版本链。 */
    public record RecomputeRequest(
            @NotBlank String requestId,
            @NotNull LocalDate asOf) {
    }

    public record ShipView(Long id, String imo, String name, LocalDate anniversaryDate) {
    }

    public record SurveyView(
            Long id, String requestId, String imo, String typeCode, String status,
            LocalDate anniversaryDate, LocalDate windowStart, LocalDate windowEnd,
            Integer extensionMonths, LocalDate boardedAt, LocalDate issuedAt) {
    }

    public record DefectView(
            Long id, Long surveyId, String severity, String description, String status,
            LocalDate foundDate, LocalDate dueDate, LocalDate rectifiedDate,
            LocalDate recheckSubmitDate, LocalDate acceptedDate) {
    }

    public record CertificateView(
            Long id, String certNo, String imo, String typeCode, Long surveyId,
            Integer version, String status, LocalDate validFrom, LocalDate validUntil,
            LocalDate recheckDue, LocalDate issuedAt, LocalDate invalidatedAt) {
    }

    public record ExtensionView(
            Long id, String requestId, String imo, String typeCode, Long certificateId,
            Integer months, String status, LocalDate oldValidUntil, LocalDate newValidUntil) {
    }

    public record LedgerView(
            Long shipId, Integer cycleYear, LocalDate anniversaryDate,
            LocalDate windowStart, LocalDate windowEnd, LocalDate extendedEnd,
            Long surveyId, LocalDate boardedAt, String state) {
    }

    /** 统一错误体。 */
    public record ErrorResponse(String error, String message) {
    }

    /** 复查结果：缺陷视图 + 若逾期连带失效的证书视图（未失效为 null）。 */
    public record RecheckResult(DefectView defect, CertificateView expiredCertificate) {
    }

    /** 迟到记录登记结果。 */
    public record LateRecordResult(Long surveyId, boolean terminal, LocalDate recordDate,
                                   String detail) {
    }

    /** 复算结果摘要。 */
    public record RecomputeResult(LocalDate asOf, int ledgerCycles, int certificateChains,
                                  String defectChainDigest) {
    }
}
