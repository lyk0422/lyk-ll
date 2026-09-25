package com.example.starter.schedule;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 排产服务出参 DTO。
 */
public final class ScheduleResponses {

    private ScheduleResponses() {
    }

    /** 轧制明细视图。 */
    public record ItemView(
            int seq,
            String contractNo,
            int widthMm,
            BigDecimal mileageKm,
            boolean warmup,
            LocalDateTime plannedStart,
            LocalDateTime plannedEnd) {
    }

    /** 换辊窗口视图。 */
    public record WindowView(LocalDateTime start, LocalDateTime end) {
    }

    /** 排产单视图。 */
    public record ScheduleView(
            long scheduleId,
            String lineCode,
            String unitNo,
            int version,
            String status,
            boolean effective,
            boolean locked,
            boolean rollChangeRequired,
            BigDecimal actualMileageKm,
            List<ItemView> items,
            List<WindowView> rollChangeWindows) {
    }

    /** 单条违例明细。 */
    public record Violation(String rule, String message, Integer itemSeq) {
    }

    /** 校验结果；violations 非空时整单拒绝（HTTP 422）。 */
    public record ValidationResult(long scheduleId, String status, List<Violation> violations) {
    }

    /** 实绩回传结果。 */
    public record ActualsResult(
            long scheduleId,
            String status,
            int accepted,
            int duplicates,
            int late,
            BigDecimal actualMileageKm,
            boolean locked,
            boolean rollChangeRequired) {
    }

    /** 日冻结结果，重复冻结幂等返回相同内容。 */
    public record FreezeResult(
            long scheduleId,
            String status,
            BigDecimal actualMileageKm,
            boolean rollChangeRequired) {
    }

    /** 统一错误报文。 */
    public record ErrorBody(String error, String message, List<Violation> violations) {
    }
}
