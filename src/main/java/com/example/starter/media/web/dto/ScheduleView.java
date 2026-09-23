package com.example.starter.media.web.dto;

import java.util.List;

/**
 * 排期视图：包含排期版本号与全部时段。
 */
public record ScheduleView(
        String scheduleId,
        Integer versionNo,
        String status,
        List<ScheduleItemView> items
) {
}
