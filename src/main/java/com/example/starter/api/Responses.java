package com.example.starter.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 响应 DTO。可空字段为 null 时表示该业务对象未产生（如非一级风险不生成修剪计划）。
 */
public final class Responses {

    private Responses() {
    }

    /** 路段建档响应。 */
    public record SegmentResponse(long id, String code, String name) {
    }

    /** 树木建档响应，planId/deadline 仅一级风险时非空。 */
    public record TreeRegisterResponse(long treeId, String treeCode, String segmentCode,
                                       double riskScore, int riskLevel,
                                       Long planId, LocalDateTime deadline) {
    }

    /** 作业许可响应，deadline 为签发时既定时限。 */
    public record PermitResponse(String permitNo, String status, LocalDateTime deadline) {
    }

    /** 作业回传响应，onTime 表示是否按期闭环（恰第7日闭环为按期）。 */
    public record ReportResponse(long reportId, String permitNo, long planId, boolean onTime) {
    }

    /** 预警发布响应，frozenSegments 为本次冻结的高风险路段编码。 */
    public record WarningResponse(String warningNo, int level, List<String> frozenSegments) {
    }

    /** 预警解除响应，liftedSegments 为解除冻结的路段编码。 */
    public record WarningCancelResponse(String warningNo, List<String> liftedSegments) {
    }

    /** 年度复评响应，rescheduled 表示评级变化触发了计划重排。 */
    public record ReevaluateResponse(String treeCode, int oldLevel, int newLevel,
                                     boolean rescheduled, Long newPlanId,
                                     LocalDateTime newDeadline) {
    }

    /**
     * 台账行：各路段风险分布与修剪闭环率。closureRate=闭环数/(待修剪+已闭环+已督办)，
     * 重排计划不计入分母；无计划时为 null。
     */
    public record LedgerEntry(String segmentCode,
                              long level1Count, long level2Count, long level3Count, long level4Count,
                              long totalPlans, long closedPlans, long escalatedPlans,
                              Double closureRate) {
    }

    /** 统一错误响应。 */
    public record ErrorResponse(String code, String message) {
    }
}
