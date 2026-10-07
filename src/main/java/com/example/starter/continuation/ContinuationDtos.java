package com.example.starter.continuation;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.example.starter.ticket.TicketDtos.StepSpec;

/**
 * 余步续票接口报文定义。
 * 单据版本从1开始，所有变更携带 expectedVersion，成功加1，旧版409。
 */
public final class ContinuationDtos {

    private ContinuationDtos() {
    }

    /** 续票申请：引用异常/作废原票，声明间隔与剩余步骤（须与原票冻结内容完全一致）。 */
    public record ApplyContinuationRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long originalTicketId,
            @NotBlank String intervalId,
            @NotBlank String title,
            @NotEmpty List<@Valid StepSpec> steps) {
    }

    /** 审核确认：两名不同审核人各确认一次。 */
    public record ReviewContinuationRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long expectedVersion,
            @NotBlank String reviewerId) {
    }

    /** 生效请求：复核原票版本与当前设备状态后，原子转移间隔执行权。 */
    public record EffectContinuationRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long expectedVersion) {
    }

    /** 撤回请求：仅未生效申请可撤回，不释放原间隔或实物。 */
    public record WithdrawContinuationRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long expectedVersion) {
    }

    /** 续票单据视图。 */
    public record ContinuationView(
            long continuationId,
            long originalTicketId,
            long newTicketId,
            String intervalId,
            String status,
            String reviewerA,
            String reviewerB,
            int originalTicketVersion,
            int version,
            List<String> inheritedWires,
            Instant effectiveAt,
            Instant createdAt,
            Instant updatedAt) {
    }
}
