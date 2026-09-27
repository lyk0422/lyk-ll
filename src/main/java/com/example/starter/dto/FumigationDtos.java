package com.example.starter.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.model.FumigationStatus;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 熏蒸闭环请求/响应对象集合：申请、批准、开始、散气采样、解除确认与查询。
 * 作业窗与采样时刻均为 UTC；版本从 1 开始，每次成功变更加 1。
 */
public final class FumigationDtos {

    private FumigationDtos() {
    }

    /**
     * 熏蒸申请请求；requestId 幂等，指纹覆盖 requestId 与全部业务字段。
     * 作业窗为 UTC 半开区间 [windowStart, windowEnd)；plan 为合成方案文本，不计算真实药剂剂量。
     */
    public record ApplyFumigationRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime windowStart,
            @NotNull LocalDateTime windowEnd,
            @NotBlank String plan,
            @NotNull BigDecimal safeThreshold) {
    }

    /** 熏蒸单视图。 */
    public record FumigationView(
            long id,
            long siloId,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            String plan,
            BigDecimal safeThreshold,
            FumigationStatus status,
            int version,
            String approver1,
            String approver2,
            Long fanId,
            Integer frozenPlanVersion,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            LocalDateTime releasedAt,
            boolean replayed) {
    }

    /** 审批请求；expectedVersion 须等于当前版本，审批人须与已有审批人不同。 */
    public record ApproveRequest(
            @NotBlank String requestId,
            @NotBlank String approver,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    /** 审批结果视图；version/status 为本次审批成功后的冻结结果。 */
    public record ApprovalView(
            long orderId,
            String approver,
            int approvals,
            int version,
            FumigationStatus status,
            boolean replayed) {
    }

    /** 开始熏蒸请求；startedAt 为实际开始时刻(UTC)，开始时再查无执行中通风。 */
    public record StartRequest(
            @NotBlank String requestId,
            @NotNull @Min(1) Integer expectedVersion,
            @NotNull LocalDateTime startedAt) {
    }

    /** 结束熏蒸转散气请求；绑定一台空闲风机，finishedAt 为结束时刻(UTC)。 */
    public record FinishRequest(
            @NotBlank String requestId,
            @NotNull @Min(1) Integer expectedVersion,
            @NotNull Long fanId,
            @NotNull LocalDateTime finishedAt) {
    }

    /**
     * 散气采样请求；seqNo 单内唯一（缺号不视为安全），measuredAt 单内严格递增(UTC)，
     * concentration 为有限非负小数。
     */
    public record GasReadingRequest(
            @NotBlank String requestId,
            @NotNull @Min(1) Integer seqNo,
            @NotNull BigDecimal concentration,
            @NotNull LocalDateTime measuredAt) {
    }

    /** 采样结果：RECORDED 已记录；LATE_RECORDED 解除后迟到，只记迟到事件不改冻结证据。 */
    public record GasReadingView(
            Long id,
            long orderId,
            Integer seqNo,
            BigDecimal concentration,
            LocalDateTime measuredAt,
            String result,
            boolean replayed) {
    }

    /** 解除确认请求；两名不同复核人最终确认时重查最新读数与版本。 */
    public record ReleaseConfirmRequest(
            @NotBlank String requestId,
            @NotBlank String reviewer,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    /** 解除确认结果视图；released/version 为本次确认成功后的冻结结果。 */
    public record ConfirmationView(
            long orderId,
            String reviewer,
            int confirmations,
            boolean released,
            int version,
            boolean replayed) {
    }

    /** 解除条件评估：最新连续三点均不超过阈值(恰等合法)且首末间隔≥30分钟，缺号不视为安全。 */
    public record ReleaseAssessment(
            boolean releasable,
            String reason,
            Integer runStartSeq,
            Integer runEndSeq,
            LocalDateTime runStartAt,
            LocalDateTime runEndAt) {
    }

    /** 熏蒸单详情：单据、审批人、复核人与当前解除条件评估。 */
    public record FumigationDetailView(
            FumigationView order,
            List<String> approvers,
            List<String> reviewers,
            ReleaseAssessment assessment) {
    }
}
