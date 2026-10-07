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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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

    /** 解除锁仓请求：两名不同复核人共同确认，须携带期望版本，确认时重查最新读数/帧与版本。 */
    public record ReleaseFumigationRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull @jakarta.validation.constraints.Size(min = 2, max = 2)
            List<@NotBlank String> reviewers) {
    }

    /**
     * 多测点名单初始登记请求：散气前登记 2~10 个互不相同测点并冻结名单版本（版本 1）。
     * 测点编码顺序参与幂等指纹，但业务上视为集合（重复编码拒绝）。
     */
    public record RegisterPointListRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotEmpty @Size(min = 2, max = 10) List<@NotBlank String> points) {
    }

    /**
     * 散气中增补测点请求：只能增加当前名单之外的新测点（1~10 个），不得删除既有测点；
     * 增补成功名单版本加 1，已形成的待解除连续帧确认失效，必须从新版首个完整帧重新累计。
     */
    public record AddPointsRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotEmpty @Size(min = 1, max = 10) List<@NotBlank String> points) {
    }

    /**
     * 单个测点浓度。pointCode 必须在采样时的在册名单内；concentration 单位 ppm，有限非负小数。
     */
    public record FramePointReading(
            @NotBlank String pointCode,
            @NotNull BigDecimal concentration) {
    }

    /**
     * 多测点采样帧请求：seqNo 单号内严格递增（缺号打断连续帧计数）；measuredAt 为全帧共用的
     * UTC 采样时刻，单号内严格递增；readings 必须恰好覆盖当前名单全部测点（缺点、重复点、未知点、
     * 非递增时刻整帧拒绝，不写部分读数）。
     */
    public record GasFrameRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull Integer seqNo,
            @NotNull Instant measuredAt,
            @NotEmpty @Valid List<@NotNull FramePointReading> readings) {
    }

    /** 测点名单视图。 */
    public record PointListView(
            int listVersion,
            List<String> points,
            boolean replayed) {
    }

    /** 帧内测点读数视图。 */
    public record FrameReadingView(
            String pointCode,
            BigDecimal concentration) {
    }

    /** 采样帧视图：worstConcentration 为本帧全点最大浓度，safe 为全点是否均不越限。 */
    public record GasFrameView(
            long id,
            long fumigationId,
            int seqNo,
            Instant measuredAt,
            int listVersion,
            BigDecimal worstConcentration,
            boolean safe,
            boolean late,
            List<FrameReadingView> readings,
            boolean replayed) {
    }

    /**
     * 有效连续安全帧段复算视图：仅统计当前名单版本下、序号连续且全部安全的最新一段帧
     * （增点后的旧版帧、越限帧、序号缺口都会打断该段）。releasable 表示该段最后三帧
     * 首末间隔 ≥30 分钟，已满足解除的读数条件；first/last 为最后三帧的首末帧，不足三帧为 null。
     */
    public record FrameChainView(
            int listVersion,
            int runLength,
            boolean releasable,
            Integer firstSeqNo,
            Integer lastSeqNo,
            Instant firstMeasuredAt,
            Instant lastMeasuredAt,
            List<GasFrameView> runFrames) {
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
            boolean multiPoint,
            Integer pointListVersion,
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

    /** 解除双复核视图；listVersion 非空表示多测点单按该名单版本的三帧解除，旧单点单为 null。 */
    public record ReleaseView(
            long id,
            long fumigationId,
            int checkedVersion,
            int lastSeqNo,
            int firstSeqNo,
            Integer listVersion,
            List<String> reviewers,
            Instant releasedAt,
            boolean replayed) {
    }
}
