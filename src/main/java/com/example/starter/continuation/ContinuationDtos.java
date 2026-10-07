package com.example.starter.continuation;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 余步续票接口报文定义。
 * 申请单版本从1开始，确认/签发/撤回均携带 expectedVersion，成功加1，旧版409。
 */
public final class ContinuationDtos {

    private ContinuationDtos() {
    }

    /** 剩余步骤冻结校验项：申请方如提供，必须与原票剩余步骤完全一致，不得删改。 */
    public record RemainingStepSpec(
            @Positive int seq,
            @NotBlank String operationType,
            @NotBlank String deviceId,
            @NotBlank String expectedState) {
    }

    /**
     * 续票申请请求：引用EXCEPTION/EXPIRED且未归档的原票。
     * intervalId 必须与原票一致（拒绝跨间隔）；remainingSteps 如提供必须与原票剩余步骤一致（拒绝删改）。
     */
    public record ApplyRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long sourceTicketId,
            @NotNull @Positive Long expectedSourceVersion,
            @NotBlank String intervalId,
            @NotBlank String title,
            List<@Valid RemainingStepSpec> remainingSteps) {
    }

    /** 双人确认请求：两名不同审核人。 */
    public record ConfirmRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long expectedVersion,
            @NotBlank String reviewerA,
            @NotBlank String reviewerB) {
    }

    /** 签发请求：复核当前设备状态与原票版本后生效，原子交接间隔执行权。 */
    public record IssueRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long expectedVersion) {
    }

    /** 撤回请求：仅未生效申请可撤回。 */
    public record WithdrawRequest(
            @NotBlank String requestId,
            @NotNull @Positive Long expectedVersion) {
    }

    /** 续票申请单视图。 */
    public record ContinuationView(
            long continuationId,
            long sourceTicketId,
            long newTicketId,
            int sourceVersion,
            String status,
            String reviewerA,
            String reviewerB,
            int version,
            Instant createdAt,
            Instant updatedAt) {
    }
}
