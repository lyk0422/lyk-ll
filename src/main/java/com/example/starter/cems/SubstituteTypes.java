package com.example.starter.cems;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 检修替代监测小时台账与有效监测统计的公共类型集合（cems-03 新增，合成数据，不用于生产）。
 * 时刻约定：UTC 精确时刻以 {@link Instant} 表示；替代小时固定 UTC 整点；
 * createdAt/frozenAt 等系统时间为 Asia/Shanghai 本地时间。
 * 单位：浓度 mg/L。补证不删改原机测读数、缺点告警、超标链与限排证据。
 */
public final class SubstituteTypes {

    private SubstituteTypes() {
    }

    /** 检修许可状态：PENDING_CONFIRM 待两人确认 / ACTIVE 已生效 / SUPERSEDED 已被取代。 */
    public enum PermitStatus {
        PENDING_CONFIRM, ACTIVE, SUPERSEDED
    }

    /** 替代小时状态：PENDING_REVIEW 待复核 / EFFECTIVE 已生效 / SUPERSEDED 已被取代 / INVALIDATED 被机测失效。 */
    public enum SubstituteStatus {
        PENDING_REVIEW, EFFECTIVE, SUPERSEDED, INVALIDATED
    }

    /** 有效监测小时来源：MACHINE 合法机测 / SUBSTITUTE 有效替代 / MISSING 缺失。 */
    public enum EffectiveSource {
        MACHINE, SUBSTITUTE, MISSING
    }

    /** 独立调差类型（冻结后不改快照，只登记调差链）。 */
    public enum AdjustType {
        MACHINE_SUPPLEMENT, SUBSTITUTE_SUBMIT, SUBSTITUTE_CORRECT, SUBSTITUTE_REVIEW
    }

    // ---------------- 请求 ----------------

    /**
     * 新增检修许可版本：expectedVersion 为当前最新版本号（无许可时传 0），
     * 版本从 1 开始、成功变更加 1；窗口为 UTC 半开区间 [windowStartUtc, windowEndUtc)。
     * 新版本须两名不同人员确认后才生效。
     */
    public record PermitUpsertRequest(
            String requestId,
            String outletCode,
            Integer expectedVersion,
            Instant windowStartUtc,
            Instant windowEndUtc,
            String createdBy
    ) {
    }

    /** 检修许可确认：两名不同人员确认后许可生效；同一确认人重复确认拒绝。 */
    public record PermitConfirmRequest(
            String requestId,
            String outletCode,
            Integer permitVersion,
            String confirmer
    ) {
    }

    /** 合成检验报告登记：报告绑定排口与 UTC 整点；跨排口或跨小时引用拒绝。 */
    public record LabReportRequest(
            String requestId,
            String reportNo,
            String outletCode,
            Instant hourUtc
    ) {
    }

    /**
     * 替代小时提交/更正：缺机测小时才可提交；expectedVersion 为该小时当前最新版本
     * （首次提交传 0），版本从 1 开始、成功更正加 1、旧版 409；
     * 须同排口同小时合成检验报告，缺报告不能按零浓度填补。
     */
    public record SubstituteSubmitRequest(
            String requestId,
            String outletCode,
            Instant hourUtc,
            BigDecimal value,
            String reportNo,
            String submittedBy,
            Integer expectedVersion
    ) {
    }

    /** 替代复核：复核人须不同于提交人，复核确认后替代才有效。 */
    public record SubstituteReviewRequest(
            String requestId,
            String outletCode,
            Instant hourUtc,
            Integer version,
            String reviewer
    ) {
    }

    /** 有效监测日结冻结：冻结当前来源选择及替代版本，之后变更仅形成独立调差。 */
    public record FreezeRequest(
            String requestId,
            String outletCode,
            LocalDate dayUtc
    ) {
    }

    // ---------------- 响应 ----------------

    /** 检修许可版本视图：confirmers 为已确认人员列表，满两名不同人员后 ACTIVE。 */
    public record PermitView(
            String outletCode,
            int version,
            Instant windowStartUtc,
            Instant windowEndUtc,
            String status,
            String createdBy,
            List<String> confirmers,
            LocalDateTime createdAt
    ) {
    }

    /** 合成检验报告视图。 */
    public record LabReportView(
            String reportNo,
            String outletCode,
            Instant hourUtc,
            LocalDateTime createdAt
    ) {
    }

    /** 替代小时台账视图。 */
    public record SubstituteView(
            String outletCode,
            Instant hourUtc,
            int version,
            String reportNo,
            BigDecimal value,
            String submittedBy,
            String reviewedBy,
            String status,
            LocalDateTime createdAt
    ) {
    }

    /**
     * 替代提交/更正结果：frozen=true 表示该日已冻结，本次仅登记独立调差、
     * 不改台账与冻结快照（版本不加）。
     */
    public record SubstituteSubmitResult(
            String requestId,
            String outletCode,
            Instant hourUtc,
            int version,
            String status,
            boolean frozen,
            boolean adjustmentRecorded
    ) {
    }

    /**
     * 替代复核结果：exceedanceMarked=true 表示替代超标已按原阈值追加独立超标来源；
     * frozen=true 表示该日已冻结，本次仅登记独立调差、不改台账。
     */
    public record SubstituteReviewResult(
            String requestId,
            String outletCode,
            Instant hourUtc,
            int version,
            String status,
            boolean exceedanceMarked,
            boolean frozen,
            boolean adjustmentRecorded
    ) {
    }

    /**
     * 有效监测均值：每小时合法机测优先，没有机测才取有效替代，两者不双计；
     * 分母（machineCount+substituteCount）、缺失小时与来源明示；空集合均值 null（无结果而非零）。
     */
    public record EffectiveAveragesView(
            String outletCode,
            Instant fromUtc,
            Instant toUtc,
            long totalSlots,
            int machineCount,
            int substituteCount,
            int missingCount,
            BigDecimal avgValue,
            List<Instant> missingHours
    ) {
    }

    /** 日结冻结槽位视图：冻结时的来源选择与替代版本。 */
    public record FreezeItemView(
            Instant hourUtc,
            String source,
            BigDecimal value,
            Integer substituteVersion
    ) {
    }

    /** 日结冻结快照：冻结当前选择及版本；空集合均值 null。 */
    public record FreezeView(
            String outletCode,
            LocalDate dayUtc,
            BigDecimal avgValue,
            int machineCount,
            int substituteCount,
            int missingCount,
            String status,
            LocalDateTime frozenAt,
            List<FreezeItemView> items
    ) {
    }

    /** 独立调差链条目：冻结后补机测或更正替代只形成调差，不重写冻结均值。 */
    public record AdjustmentView(
            String outletCode,
            LocalDate dayUtc,
            Instant hourUtc,
            String adjustType,
            String oldSource,
            BigDecimal oldValue,
            String newSource,
            BigDecimal newValue,
            Integer substituteVersion,
            String requestId,
            LocalDateTime createdAt
    ) {
    }

    /** 替代超标独立来源视图：只留证不能删除；SUPERSEDED 表示被机测或新版本取代。 */
    public record SubstituteExceedanceView(
            String outletCode,
            Instant hourUtc,
            BigDecimal limitValue,
            BigDecimal actualValue,
            int substituteVersion,
            String status,
            LocalDateTime createdAt
    ) {
    }
}
