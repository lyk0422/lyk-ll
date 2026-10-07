package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 分段计划详情：当前版本状态/进度/期限、机组斜率与逐分钟冻结阶梯（含确认情况），可复算。
 */
public record StagedPlanDetailResponse(
        String orderNo,
        Long planId,
        int planVersion,
        String planStatus,
        Instant startAt,
        int currentMinute,
        int durationMinutes,
        List<StagedTurbineView> turbines,
        List<MinuteView> minutes) {

    /**
     * 单分钟视图：minuteAt=启动时刻+序号分钟；confirmed 表示该分钟已整分钟确认。
     */
    public record MinuteView(
            int minuteNo,
            Instant minuteAt,
            boolean confirmed,
            Instant confirmedAt,
            List<StagedReceiptItem> items) {
    }
}
