package com.example.starter.groundwire;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 接地线实物联锁接口报文定义。
 * 所有写操作携带 requestId、expectedVersion 与请求精确时刻 occurredAt（UTC）。
 */
public final class GroundWireDtos {

    private GroundWireDtos() {
    }

    /** 借用/归还明细：接地线编号与客户端持有的期望版本（版本从 1 开始）。 */
    public record WireVersionItem(
            @NotBlank String wireNo,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    /** 借用请求：绑定未归档且未作废的操作票与间隔，一次可借多条。 */
    public record BorrowRequest(
            @NotBlank String requestId,
            @NotBlank String intervalId,
            @NotEmpty List<@Valid WireVersionItem> items,
            @NotNull Instant occurredAt) {
    }

    /** 挂接请求：引用本票步骤，操作人与监护人双确认。 */
    public record ApplyRequest(
            @NotBlank String requestId,
            @NotNull @Min(1) Integer expectedVersion,
            @NotNull @Min(1) Integer stepSeq,
            @NotBlank String operatorId,
            @NotBlank String guardianId,
            @NotNull Instant occurredAt) {
    }

    /** 拆除请求：引用本票步骤，操作人与监护人双确认。 */
    public record RemoveRequest(
            @NotBlank String requestId,
            @NotNull @Min(1) Integer expectedVersion,
            @NotNull @Min(1) Integer stepSeq,
            @NotBlank String operatorId,
            @NotBlank String guardianId,
            @NotNull Instant occurredAt) {
    }

    /** 归还请求：仓管逐条核对编号与版本，只核销已拆除实物。 */
    public record ReturnRequest(
            @NotBlank String requestId,
            @NotEmpty List<@Valid WireVersionItem> items,
            @NotBlank String warehouseKeeperId,
            @NotNull Instant occurredAt) {
    }

    /** 接地线实物视图。 */
    public record WireView(
            long wireId,
            String wireNo,
            String status,
            String intervalId,
            Long currentTicketId,
            int version,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 借用结果视图。 */
    public record BorrowView(
            String requestId,
            long ticketId,
            String intervalId,
            List<WireView> wires) {
    }

    /** 归还结果视图。 */
    public record ReturnView(
            String requestId,
            long ticketId,
            String warehouseKeeperId,
            List<WireView> wires) {
    }
    /** 挂接/拆除操作证据视图，证据只追加不可覆盖。 */
    public record OperationView(
            long id,
            String requestId,
            long ticketId,
            String wireNo,
            int stepSeq,
            String action,
            String operatorId,
            String guardianId,
            Instant operatedAt,
            Instant createdAt) {
    }

    /** 借用/核销登记视图，历史记录保留。 */
    public record BorrowRecordView(
            long id,
            String borrowRequestId,
            String returnRequestId,
            long ticketId,
            String intervalId,
            String wireNo,
            String status,
            String warehouseKeeperId,
            Instant borrowedAt,
            Instant returnedAt) {
    }
}
