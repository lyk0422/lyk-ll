package com.example.starter.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.example.starter.model.FumigationStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 熏蒸作业请求/响应对象集合。作业窗与采样时刻均为 UTC 精确时刻（ISO-8601）。
 * 写操作均携带 requestId 幂等；修改操作携带 expectedVersion 乐观锁。
 */
public final class FumigationDtos {

    private FumigationDtos() {
    }

    /**
     * 熏蒸申请请求；requestId 幂等。作业窗为 UTC 半开区间 [windowStart, windowEnd)，
     * planSteps 为合成方案步骤（不计算真实药剂剂量），指纹覆盖该集合。
     */
    public record CreateFumigationRequest(
            @NotBlank String requestId,
            @NotNull Instant windowStart,
            @NotNull Instant windowEnd,
            @NotNull BigDecimal safetyThreshold,
            @NotNull List<@NotBlank String> planSteps) {
    }

    /** 审批请求；expectedVersion 须等于当前版本，两名不同审批人批准后方可开始。 */
    public record ApproveRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotBlank String approver) {
    }

    /** 开始熏蒸请求；原子锁仓并冻结方案版本。 */
    public record StartFumigationRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion) {
    }

    /** 结束熏蒸转散气请求；绑定一台空闲风机。 */
    public record FinishFumigationRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull Long fanId) {
    }

    /**
     * 散气合成气体读数请求；seq 同一单内唯一，measuredAt 为 UTC 精确时刻且严格递增，
     * concentration 为有限非负小数。
     */
    public record GasReadingRequest(
            @NotBlank String requestId,
            @NotNull Integer seq,
            @NotNull BigDecimal concentration,
            @NotNull Instant measuredAt) {
    }

    /** 解除复核请求；须两名不同复核人，最终确认时重查最新读数与版本。 */
    public record ReleaseConfirmRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotBlank String reviewer) {
    }

    /** 熏蒸单视图；时间为 UTC 精确时刻，version 从 1 开始每次成功变更加 1。 */
    public record FumigationView(
            long id,
            long siloId,
            Instant windowStart,
            Instant windowEnd,
            BigDecimal safetyThreshold,
            List<String> planSteps,
            FumigationStatus status,
            int version,
            Integer frozenVersion,
            Long fanId,
            List<String> approvers,
            String releaseReviewer1,
            Instant startedAt,
            Instant aerationStartedAt,
            Instant releasedAt,
            boolean replayed) {

        /** 返回重放标记后的副本（冻结结果重放时使用）。 */
        public FumigationView withReplayed(boolean value) {
            return new FumigationView(id, siloId, windowStart, windowEnd, safetyThreshold, planSteps,
                    status, version, frozenVersion, fanId, approvers, releaseReviewer1,
                    startedAt, aerationStartedAt, releasedAt, value);
        }
    }

    /** 散气气体读数视图。 */
    public record GasReadingView(
            long id,
            long fumigationId,
            int seq,
            BigDecimal concentration,
            Instant measuredAt) {
    }

    /**
     * 散气采样结果：RECORDED 已记录、LATE_RECORDED 解除后迟到仅记事件（不改已冻结证据）。
     */
    public record GasReadingResponse(
            String result,
            Long id,
            Integer seq,
            boolean replayed) {
    }
}
