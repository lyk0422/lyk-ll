package com.example.starter.ground;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 临时接地线借用核销接口报文定义。
 */
public final class GroundWireDtos {

    private GroundWireDtos() {
    }

    /** 批量借还条目：接地线编号与期望版本。 */
    public record WireItem(
            @NotBlank String wireNo,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    /** 借用请求：绑定未归档且未作废的操作票，一次可借多条，任一不可用整批拒绝。 */
    public record BorrowRequest(
            @NotBlank String requestId,
            @NotNull Long ticketId,
            @NotEmpty List<@Valid WireItem> items,
            @NotNull Instant occurredAt) {
    }

    /** 挂接请求：引用本票步骤，操作人与监护人两名不同人员共同确认。 */
    public record AttachRequest(
            @NotBlank String requestId,
            @NotNull Long ticketId,
            @NotBlank String wireNo,
            @NotNull @Min(1) Integer stepSeq,
            @NotBlank String operatorId,
            @NotBlank String guardianId,
            @NotNull @Min(1) Integer expectedVersion,
            @NotNull Instant occurredAt) {
    }

    /** 拆除请求：操作人与监护人两名不同人员共同确认。 */
    public record DetachRequest(
            @NotBlank String requestId,
            @NotNull Long ticketId,
            @NotBlank String wireNo,
            @NotBlank String operatorId,
            @NotBlank String guardianId,
            @NotNull @Min(1) Integer expectedVersion,
            @NotNull Instant occurredAt) {
    }

    /** 归还核销请求：仓管核对编号，只核销已拆除实物。 */
    public record ReturnRequest(
            @NotBlank String requestId,
            @NotNull Long ticketId,
            @NotBlank String keeperId,
            @NotEmpty List<@Valid WireItem> items,
            @NotNull Instant occurredAt) {
    }

    /** 接地线视图。 */
    public record WireView(
            String wireNo,
            String status,
            Long holderTicketId,
            String intervalId,
            int version,
            Instant updatedAt) {
    }

    /** 操作证据视图：只插入不更新，不可覆盖。 */
    public record WireEventView(
            long id,
            String wireNo,
            long ticketId,
            String action,
            Integer stepSeq,
            String operatorId,
            String guardianId,
            String keeperId,
            Instant occurredAt) {
    }

    /** 接地线详情：台账与全部操作证据。 */
    public record WireDetailView(
            WireView wire,
            List<WireEventView> events) {
    }

    /** 借用/归还批次结果。 */
    public record BatchView(
            long ticketId,
            List<WireView> wires) {
    }

    /** 操作票维度接地线视图：当前持有与历史证据。 */
    public record TicketGroundView(
            long ticketId,
            List<WireView> heldWires,
            List<WireEventView> events) {
    }
}
