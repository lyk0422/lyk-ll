package com.example.starter.cems;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 监测仪检修期人工替代证据与有效监测统计的公共类型集合（cems-03 新增，合成数据，不用于生产）。
 * 时刻约定：检修许可窗口、检验报告窗口与替代小时均为 UTC 精确时刻/整点；
 * createdAt/confirmedAt/frozenAt 为 Asia/Shanghai 本地时间。
 * 单位：替代浓度 mg/L，阈值沿用排口原许可 hourly_limit（含本值达标）。
 * 补证不删除原数据缺点告警、超标链与限排令；替代超标仅追加独立来源。
 */
public final class MaintenanceTypes {

    private MaintenanceTypes() {
    }

    /** 替代小时版本状态：PENDING 待复核 / EFFECTIVE 当前有效 / SUPERSEDED 被新版本替代 / INVALIDATED 补到合法机测后失效。 */
    public enum ReplacementStatus {
        PENDING, EFFECTIVE, SUPERSEDED, INVALIDATED
    }

    /** 有效监测口径小时来源：MACHINE 合法机测优先 / REPLACEMENT 无机测时取有效替代。 */
    public enum EffectiveSource {
        MACHINE, REPLACEMENT
    }

    /** 封账后独立调差类型：MACHINE_BACKFILL 补到合法机测 / REPLACEMENT_CORRECTION 更正替代。 */
    public enum AdjustmentType {
        MACHINE_BACKFILL, REPLACEMENT_CORRECTION
    }

    /** 独立替代超标留痕状态：ACTIVE 随当前替代有效 / SUPERSEDED 被新替代版本取代 / INVALIDATED 因机测补入失效；只留证不删除。 */
    public enum SubstituteExceedanceStatus {
        ACTIVE, SUPERSEDED, INVALIDATED
    }

    // ---------------- 请求 ----------------

    /**
     * 新增检修许可版本：绑定排口与 UTC 半开窗口 [windowStartUtc, windowEndUtc)，
     * 须两名不同人员 confirmerA/confirmerB 确认；expectedVersion 为当前最新版本（无许可传 0），版本从 1 起。
     */
    public record MaintenancePermitRequest(
            String requestId,
            String outletCode,
            Integer expectedVersion,
            Instant windowStartUtc,
            Instant windowEndUtc,
            String confirmerA,
            String confirmerB
    ) {
    }

    /** 合成检验报告登记：绑定排口，UTC 半开采样窗口；用于替代小时时必须与该小时 [hour, hour+1) 精确一致。 */
    public record LabReportRequest(
            String requestId,
            String outletCode,
            String reportNo,
            Instant windowStartUtc,
            Instant windowEndUtc
    ) {
    }

    /**
     * 缺机测小时提交替代证据：有限非负浓度 + 同排口合成检验报告 + UTC 整点；
     * expectedVersion 为该小时当前有效替代版本（无则传 0）。提交后须另一人复核才有效。
     */
    public record ReplacementSubmitRequest(
            String requestId,
            String outletCode,
            Instant hourUtc,
            BigDecimal concentration,
            String reportNo,
            String submitter,
            Integer expectedVersion
    ) {
    }

    /** 替代复核：reviewer 必须不同于提交人；复核成功版本生效，旧版本失效。 */
    public record ReplacementConfirmRequest(
            String requestId,
            String outletCode,
            Instant hourUtc,
            String reviewer
    ) {
    }

    /** 有效监测日结冻结：冻结当前逐小时选择（机测优先）与替代版本。 */
    public record MaintenanceReviewRequest(
            String requestId,
            String outletCode,
            LocalDate dayUtc
    ) {
    }

    // ---------------- 响应 ----------------

    /** 检修许可版本视图。 */
    public record MaintenancePermitView(
            String outletCode,
            int version,
            Instant windowStartUtc,
            Instant windowEndUtc,
            String confirmerA,
            String confirmerB,
            String status,
            LocalDateTime createdAt,
            LocalDateTime confirmedAt
    ) {
    }

    /** 合成检验报告视图。 */
    public record LabReportView(
            String reportNo,
            String outletCode,
            Instant windowStartUtc,
            Instant windowEndUtc,
            LocalDateTime createdAt
    ) {
    }

    /** 替代小时版本视图：reviewer/confirmedAt 仅生效携带。 */
    public record ReplacementView(
            String outletCode,
            Instant hourUtc,
            int version,
            int expectedVersion,
            BigDecimal concentration,
            String reportNo,
            String submitter,
            String reviewer,
            String status,
            LocalDateTime createdAt,
            LocalDateTime confirmedAt
    ) {
    }

    /** 有效监测口径逐小时选择：来源明示，替代来源携带版本与报告号。 */
    public record EffectiveHourView(
            Instant hourUtc,
            String source,
            BigDecimal concentration,
            Integer replacementVersion,
            String reportNo
    ) {
    }

    /**
     * 有效监测均值：机测优先、无机测才取有效替代，两者不双计；
     * 分母 countedCount、缺失小时 missingHours 明示；空集合均值 null（无结果而非零）。
     */
    public record EffectiveAveragesView(
            String outletCode,
            Instant fromUtc,
            Instant toUtc,
            long totalSlots,
            int countedCount,
            int missingCount,
            BigDecimal effectiveAvg,
            List<Instant> missingHours,
            List<EffectiveHourView> hours
    ) {
    }

    /** 日结冻结的逐小时选择：来源、值与替代版本按封账时刻固化。 */
    public record MaintenanceReviewItemView(
            Instant hourUtc,
            String source,
            BigDecimal concentration,
            Integer replacementVersion,
            String reportNo
    ) {
    }

    /** 有效监测日结快照：冻结后补机测/更正替代只形成独立调差，不改本快照。 */
    public record MaintenanceReviewView(
            String outletCode,
            LocalDate dayUtc,
            Integer permitVersion,
            BigDecimal effectiveAvg,
            int countedCount,
            int missingCount,
            String status,
            LocalDateTime frozenAt,
            List<MaintenanceReviewItemView> items
    ) {
    }

    /** 封账后独立调差视图：旧选择→新选择留痕，不重写冻结均值。 */
    public record MaintenanceAdjustmentView(
            long id,
            String outletCode,
            LocalDate dayUtc,
            Instant hourUtc,
            String changeType,
            String oldSource,
            BigDecimal oldValue,
            String newSource,
            BigDecimal newValue,
            Integer replacementVersion,
            String requestId,
            LocalDateTime createdAt
    ) {
    }

    /** 替代超标独立来源视图：按原 hourly_limit 阈值追加，只留证不删除。 */
    public record SubstituteExceedanceView(
            long id,
            String outletCode,
            Instant hourUtc,
            BigDecimal limitValue,
            BigDecimal actualValue,
            int replacementVersion,
            String status,
            LocalDateTime createdAt
    ) {
    }
}
