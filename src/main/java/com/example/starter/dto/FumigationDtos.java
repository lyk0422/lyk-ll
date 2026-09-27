package com.example.starter.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.example.starter.model.FumigationStatus;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 熏蒸审批与散气解除联锁的请求/响应对象集合。
 * 作业窗与采样/审批时刻均为 UTC Instant；浓度为无量纲合成单位。
 */
public final class FumigationDtos {

    private FumigationDtos() {
    }

    /**
     * 熏蒸申请请求；requestId 幂等。作业窗为 UTC 半开区间 [windowStart, windowEnd)。
     * safeThreshold 为合成安全阈值，有限非负小数，读数≤阈值判安全（恰等合法）。
     */
    public record CreateFumigationRequest(
            @NotBlank String requestId,
            @NotNull Instant windowStart,
            @NotNull Instant windowEnd,
            @NotNull @DecimalMin("0") BigDecimal safeThreshold) {
    }

    /**
     * 批准请求；expectedVersion 乐观版本，decidedAt 为批准精确时刻（UTC）。
     * 两名不同审批人共同批准后方可开始。
     */
    public record ApproveRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotBlank String approver,
            @NotNull Instant decidedAt) {
    }

    /** 开始熏蒸请求；startedAt 为开始精确时刻（UTC）。 */
    public record StartRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull Instant startedAt) {
    }

    /** 结束熏蒸转散气请求；绑定一台空闲风机，finishedAt 为结束精确时刻（UTC）。 */
    public record FinishRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull Long fanId,
            @NotNull Instant finishedAt) {
    }

    /**
     * 散气采样请求；seqNo 与单号联合唯一（缺号不视为安全），concentration 为有限非负小数，
     * measuredAt 为采样精确时刻（UTC），同一单内严格递增。
     */
    public record SampleRequest(
            @NotBlank String requestId,
            @NotNull @Min(1) Integer seqNo,
            @NotNull @DecimalMin("0") BigDecimal concentration,
            @NotNull Instant measuredAt) {
    }

    /**
     * 解除请求；两名不同复核人最终确认，releasedAt 为解除精确时刻（UTC）。
     * 解除时重查最新读数与版本，原子结束散气、释放仓锁与风机。
     */
    public record ReleaseRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotBlank String reviewerOne,
            @NotBlank String reviewerTwo,
            @NotNull Instant releasedAt) {
    }

    /** 熏蒸单视图；version 从 1 开始，每次成功变更加 1。 */
    public record FumigationView(
            long id,
            long siloId,
            Instant windowStart,
            Instant windowEnd,
            BigDecimal safeThreshold,
            FumigationStatus status,
            int version,
            Long fanId,
            List<String> approvers,
            Integer frozenPlanVersion,
            boolean replayed) {

        /** 返回重放标记的副本（首次冻结结果的重放）。 */
        public FumigationView asReplayed() {
            return new FumigationView(id, siloId, windowStart, windowEnd, safeThreshold, status,
                    version, fanId, approvers, frozenPlanVersion, true);
        }
    }

    /** 散气读数视图；late=true 表示解除后迟到仅记事件，id 为 null。 */
    public record SampleView(
            Long id,
            long fumigationId,
            int seqNo,
            BigDecimal concentration,
            Instant measuredAt,
            boolean late,
            boolean replayed) {

        /** 返回重放标记的副本（首次冻结结果的重放）。 */
        public SampleView asReplayed() {
            return new SampleView(id, fumigationId, seqNo, concentration, measuredAt, late, true);
        }
    }
}
