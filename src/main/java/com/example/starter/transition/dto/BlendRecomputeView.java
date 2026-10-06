package com.example.starter.transition.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 掺配版本链复算视图：历史订单可按版本复算实际掺配轨迹，
 * 偏差与失控点由存储读数实时重算，不依赖登记时的冗余列。
 */
public record BlendRecomputeView(
        String orderNo,
        List<VersionTrajectory> versions,
        List<ControlEventView> events) {

    /** 单个比例版本的实际掺配轨迹。 */
    public record VersionTrajectory(
            Integer versionNo,
            BigDecimal targetRatioPct,
            String adjustMode,
            List<RecomputedReading> readings,
            Boolean outOfControl) {
    }

    /** 重算后的单点读数：偏差 = |实际-目标|，恰 2% 合格。 */
    public record RecomputedReading(
            LocalDateTime sampledAt,
            BigDecimal actualRatioPct,
            BigDecimal deviationPct,
            Boolean withinTolerance) {
    }

    /** 控制事件留痕（失控暂停/复核恢复）。 */
    public record ControlEventView(
            String eventType,
            Integer versionNo,
            String reviewer,
            LocalDateTime eventTime) {
    }
}
