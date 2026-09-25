package com.example.starter.runway.dto;

import java.util.List;

/**
 * 关闭事件详情：事件、状态时间线、系数轨迹（巡检记录）与通告记录，均可复算。
 */
public record ClosureDetailView(
        ClosureEventView event,
        List<TimelineEntryView> timeline,
        List<InspectionView> inspections,
        List<NotamView> notams) {
}
