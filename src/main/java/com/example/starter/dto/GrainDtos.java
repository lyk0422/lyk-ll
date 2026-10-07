package com.example.starter.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.model.FumigationStatus;
import com.example.starter.model.JobStatus;
import com.example.starter.model.RecheckDecision;
import com.example.starter.model.SiloStatus;
import com.example.starter.model.VentilationPurpose;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 粮情测控服务请求/响应对象集合。
 */
public final class GrainDtos {

    private GrainDtos() {
    }

    /** 创建粮仓请求；安全温度单位℃，平衡湿度单位%RH。 */
    public record CreateSiloRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull BigDecimal safeTemperature,
            @NotNull BigDecimal equilibriumHumidity) {
    }

    /** 粮仓视图。 */
    public record SiloView(
            long id,
            String code,
            String name,
            BigDecimal safeTemperature,
            BigDecimal equilibriumHumidity,
            SiloStatus status) {
    }

    /** 创建风机请求。 */
    public record CreateFanRequest(
            @NotBlank String code,
            @NotBlank String name) {
    }

    /** 风机视图。 */
    public record FanView(
            long id,
            String code,
            String name,
            String status,
            Long lockedJobId) {
    }

    /**
     * 温湿度上报请求；requestId 幂等，指纹覆盖 requestId 与全部业务字段。
     * 温度单位℃，湿度单位%RH，measuredAt 为 Asia/Shanghai 本地时间。
     */
    public record ReadingRequest(
            @NotBlank String requestId,
            @NotNull Integer pointNo,
            @NotNull BigDecimal temperature,
            BigDecimal outsideHumidity,
            @NotNull LocalDateTime measuredAt) {
    }

    /**
     * 温湿度上报结果：NORMAL 正常（含恰等安全温度）、ALARM 已告警、LATE_RECORDED 归档后迟到仅记事件。
     */
    public record ReadingResponse(
            String result,
            Long alarmId,
            SiloStatus siloStatus,
            boolean replayed) {
    }

    /**
     * 通风作业下达请求；requestId 幂等。作业窗为半开区间 [windowStart, windowEnd)。
     */
    public record CreateJobRequest(
            @NotBlank String requestId,
            @NotNull Long fanId,
            @NotNull VentilationPurpose purpose,
            @NotNull LocalDateTime windowStart,
            @NotNull LocalDateTime windowEnd,
            @NotNull BigDecimal outsideHumidity) {
    }

    /** 通风作业视图。 */
    public record JobView(
            long id,
            long siloId,
            long fanId,
            VentilationPurpose purpose,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            BigDecimal outsideHumidity,
            int roundNo,
            JobStatus status,
            boolean replayed) {
    }

    /**
     * 复测请求；梯度与限值单位℃，超过限值判超标，恰等判合格。
     */
    public record RecheckRequest(
            @NotBlank String requestId,
            @NotNull BigDecimal gradient,
            @NotNull BigDecimal gradientLimit,
            @NotNull LocalDateTime measuredAt) {
    }

    /** 复测视图。 */
    public record RecheckView(
            long id,
            long jobId,
            long siloId,
            int roundNo,
            BigDecimal gradient,
            BigDecimal gradientLimit,
            boolean exceeded,
            RecheckDecision decision,
            boolean replayed) {
    }

    /** 温度曲线点。 */
    public record TemperaturePoint(
            int pointNo,
            BigDecimal temperature,
            LocalDateTime measuredAt) {
    }

    /** 迟到事件视图。 */
    public record LateEventView(
            long id,
            long siloId,
            String requestId,
            String eventType,
            String payload,
            LocalDateTime recordedAt) {
    }

    /**
     * 熏蒸申请请求；作业窗为 UTC 半开区间 [windowStart, windowEnd)，端点相接可共存。
     * syntheticAgent 仅为合成方案标识，不计算真实药剂剂量；safetyThreshold 为合成安全浓度，单位 ppm。
     */
    public record ApplyFumigationRequest(
            @NotBlank String requestId,
            @NotNull Instant windowStart,
            @NotNull Instant windowEnd,
            @NotBlank String syntheticAgent,
            @NotNull BigDecimal safetyThreshold) {
    }

    /** 熏蒸审批请求：expectedVersion 为客户端期望版本；两名审批人须不同，各批准一次。 */
    public record ApproveFumigationRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotBlank String approver) {
    }

    /** 熏蒸实际开始请求：startedAt 为 UTC 精确时刻，开始时再查无执行中普通通风。 */
    public record StartFumigationRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull Instant startedAt) {
    }

    /** 结束熏蒸转散气请求：绑定一台空闲风机，aerationStartedAt 为 UTC 精确时刻。 */
    public record BeginAerationRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull Long fanId,
            @NotNull Instant aerationStartedAt) {
    }

    /** 散气合成气体采样请求：seqNo 单号内唯一且时刻严格递增；concentration 单位 ppm，有限非负小数。 */
    public record GasReadingRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull Integer seqNo,
            @NotNull BigDecimal concentration,
            @NotNull Instant measuredAt) {
    }

    /** 解除锁仓请求：两名不同复核人共同确认，须携带期望版本，确认时重查最新读数与版本。 */
    public record ReleaseFumigationRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull @jakarta.validation.constraints.Size(min = 2, max = 2)
            List<@NotBlank String> reviewers) {
    }

    /** 熏蒸单视图；时间均为 UTC。 */
    public record FumigationView(
            long id,
            long siloId,
            String orderNo,
            Instant windowStart,
            Instant windowEnd,
            String syntheticAgent,
            int planVersion,
            Integer frozenPlanVersion,
            BigDecimal safetyThreshold,
            FumigationStatus status,
            Long fanId,
            Instant startedAt,
            Instant aerationStartedAt,
            Instant releasedAt,
            int version,
            List<String> approvers,
            boolean replayed) {
    }

    /** 气体读数视图；late=TRUE 表示解除后的迟到读数，不参与安全判定。 */
    public record GasReadingView(
            long id,
            long fumigationId,
            int seqNo,
            BigDecimal concentration,
            Instant measuredAt,
            boolean late,
            boolean replayed) {
    }

    /** 解除双复核视图；checkedRosterVersion 为确认时依据的名单版本，null 表示未启用多测点的旧单。 */
    public record ReleaseView(
            long id,
            long fumigationId,
            int checkedVersion,
            int lastSeqNo,
            int firstSeqNo,
            Integer checkedRosterVersion,
            List<String> reviewers,
            Instant releasedAt,
            boolean replayed) {
    }

    /**
     * 登记测点名单请求：散气前登记 2 至 10 个不同测点并冻结为名单版本 1；
     * 登记后该单成为受控单，旧单点采样入口拒绝。
     */
    public record RegisterRosterRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull @jakarta.validation.constraints.Size(min = 2, max = 10)
            List<@NotBlank String> points) {
    }

    /** 增测点请求：散气期间可增点（每次至少 1 个新点，总数不超过 10），不得删点；成功生成新名单版本。 */
    public record AddRosterPointsRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull @jakarta.validation.constraints.Size(min = 1, max = 10)
            List<@NotBlank String> points) {
    }

    /** 测点名单视图；points 按编码排序。 */
    public record RosterView(
            long id,
            long fumigationId,
            int rosterVersion,
            List<String> points,
            boolean replayed) {
    }

    /** 采样帧单点读数：pointCode 必须属于当前名单版本；concentration 单位 ppm，有限非负小数。 */
    public record FramePointReading(
            @NotBlank String pointCode,
            @NotNull BigDecimal concentration) {
    }

    /**
     * 多测点采样帧请求：全名单各点共用同一 UTC 采样时刻；
     * 缺点、重复点、未知点或非递增时刻全帧拒绝，不写部分读数。
     */
    public record GasFrameRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull Integer seqNo,
            @NotNull Instant measuredAt,
            @NotNull @jakarta.validation.constraints.Size(min = 1, max = 10)
            List<@jakarta.validation.Valid FramePointReading> readings) {
    }

    /** 帧测点读数视图。 */
    public record FramePointView(
            String pointCode,
            BigDecimal concentration) {
    }

    /** 采样帧视图；maxConcentration 为本帧最差值，safe 为全点≤阈值。 */
    public record GasFrameView(
            long id,
            long fumigationId,
            int rosterVersion,
            int seqNo,
            Instant measuredAt,
            BigDecimal maxConcentration,
            boolean safe,
            boolean late,
            List<FramePointView> points,
            boolean replayed) {
    }

    /**
     * 安全连续段复算视图：按当前名单版本的非迟到帧重算有效连续安全段，
     * releasable 表示最近三帧连续安全且首末间隔 ≥ 30 分钟（恰等合法）。
     */
    public record GasSafetyView(
            long fumigationId,
            int rosterVersion,
            int consecutiveSafeFrames,
            Integer firstSeqNo,
            Integer lastSeqNo,
            Instant firstMeasuredAt,
            Instant lastMeasuredAt,
            Long gapSeconds,
            boolean releasable) {
    }
}
