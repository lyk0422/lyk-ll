package com.example.starter.ground;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 实物接地线借用、挂接、拆除、归还与查询接口报文定义。
 * 所有写操作携带 expectedVersion（从1开始），成功变更后版本加1。
 */
public final class GroundWireDtos {

    private GroundWireDtos() {
    }

    /** 批量借用请求：绑定未归档且未作废的操作票，一次可借多条，任一不可用整批拒绝。 */
    public record BorrowWiresRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long ticketId,
            @NotNull @Positive Long expectedVersion,
            @NotEmpty List<@NotBlank String> wireCodes,
            @NotNull Instant occurredAt) {
    }

    /** 批量归还请求：仅核销已拆除待归还实物，须仓管核对编号。 */
    public record ReturnWiresRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long ticketId,
            @NotNull @Positive Long expectedVersion,
            @NotEmpty List<@NotBlank String> wireCodes,
            @NotBlank String keeperId,
            @NotNull Instant occurredAt) {
    }

    /** 挂接请求：引用本票 APPLY_GROUND 步骤，操作人与监护人双人确认。 */
    public record AttachWireRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long ticketId,
            @NotNull @Positive Long expectedVersion,
            @Positive int stepSeq,
            @NotBlank String wireCode,
            @NotBlank String operatorId,
            @NotBlank String guardianId,
            @NotNull Instant occurredAt) {
    }

    /** 拆除请求：引用本票 REMOVE_GROUND 步骤，双人确认，证据不可覆盖。 */
    public record DetachWireRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long ticketId,
            @NotNull @Positive Long expectedVersion,
            @Positive int stepSeq,
            @NotBlank String wireCode,
            @NotBlank String operatorId,
            @NotBlank String guardianId,
            @NotNull Instant occurredAt) {
    }

    /** 实物接地线视图。 */
    public record WireView(
            String code,
            String status,
            String intervalId,
            Long occupiedByTicket,
            int version,
            Instant updatedAt) {
    }

    /** 借用/归还历史视图（归还后保留）。 */
    public record LoanView(
            long loanId,
            long ticketId,
            String intervalId,
            String wireCode,
            String status,
            Instant borrowedAt,
            Instant returnedAt,
            String returnRequestId,
            String keeperId) {
    }

    /** 挂接/拆除双人确认证据视图（只追加，不可覆盖）。 */
    public record EvidenceView(
            long evidenceId,
            String wireCode,
            long ticketId,
            int stepSeq,
            String action,
            String operatorId,
            String guardianId,
            Instant occurredAt,
            String requestId,
            Instant createdAt) {
    }

    /** 实物明细：台账状态、借用历史与操作证据。 */
    public record WireDetailView(
            WireView wire,
            List<LoanView> loans,
            List<EvidenceView> evidences) {
    }

    /** 一张票关联的实物借用与证据汇总。 */
    public record TicketGroundView(
            long ticketId,
            List<LoanView> loans,
            List<EvidenceView> evidences) {
    }

    /** 批量借用/归还结果：成功后各实物的新版本号。 */
    public record WireBatchResult(
            String action,
            int version,
            List<String> wireCodes,
            Instant occurredAt) {
    }

    /** 挂接/拆除结果。 */
    public record WireStepResult(
            String action,
            String wireCode,
            int stepSeq,
            String wireStatus,
            int version,
            Instant occurredAt) {
    }
}
