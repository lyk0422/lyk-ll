package com.example.starter.groundwire;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 临时接地线借用核销接口报文定义。
 * 所有写操作携带 requestId 幂等；修改操作携带 expectedVersion 乐观锁（版本从1开始）。
 */
public final class GroundWireDtos {

    private GroundWireDtos() {
    }

    /** 借用条目：接地线编号与期望版本。 */
    public record BorrowItem(
            @NotBlank String wireNo,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    /** 批量借用请求：绑定未归档且未作废的操作票及间隔，任一不可用整批拒绝。 */
    public record BorrowRequest(
            @NotBlank String requestId,
            @NotNull Long ticketId,
            @NotBlank String intervalId,
            Instant operatedAt,
            @NotEmpty List<@Valid BorrowItem> wires) {
    }

    /** 挂接请求：引用本票步骤，操作人与监护人双确认。 */
    public record ApplyRequest(
            @NotBlank String requestId,
            @NotNull Long ticketId,
            @NotNull @Min(1) Integer stepSeq,
            @NotBlank String operatorId,
            @NotBlank String guardianId,
            @NotNull @Min(1) Integer expectedVersion,
            Instant operatedAt) {
    }

    /** 拆除请求：引用本票步骤，操作人与监护人双确认。 */
    public record RemoveRequest(
            @NotBlank String requestId,
            @NotNull Long ticketId,
            @NotNull @Min(1) Integer stepSeq,
            @NotBlank String operatorId,
            @NotBlank String guardianId,
            @NotNull @Min(1) Integer expectedVersion,
            Instant operatedAt) {
    }

    /** 归还核销请求：仓管核对编号，仅核销已拆除实物。 */
    public record ReturnRequest(
            @NotBlank String requestId,
            @NotBlank String keeperId,
            @NotNull @Min(1) Integer expectedVersion,
            Instant operatedAt) {
    }

    /** 接地线视图。 */
    public record GroundWireView(
            long id,
            String wireNo,
            String status,
            int version,
            Long ticketId,
            String intervalId,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 挂接/拆除证据视图。 */
    public record GroundWireEventView(
            long id,
            long wireId,
            long ticketId,
            int stepSeq,
            String action,
            String operatorId,
            String guardianId,
            Instant createdAt) {
    }

    /** 借用/归还历史视图。 */
    public record LoanView(
            long id,
            long wireId,
            long ticketId,
            String intervalId,
            Instant borrowedAt,
            Instant returnedAt,
            String keeperId) {
    }

    /** 批量借用结果。 */
    public record BorrowView(
            long ticketId,
            String intervalId,
            List<GroundWireView> wires) {
    }

    /** 接地线详情：实物、证据与借用历史。 */
    public record GroundWireDetailView(
            GroundWireView wire,
            List<GroundWireEventView> events,
            List<LoanView> loans) {
    }
}
