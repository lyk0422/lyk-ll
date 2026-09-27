package com.example.starter.pump.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分区工况轨迹查询结果：当前工况、版本与历史切换。
 *
 * @param zoneCode       流域分区代码
 * @param currentMode    当前工况 NORMAL/FORCED
 * @param currentVersion 当前工况版本
 * @param switches       按时刻排序的历史切换轨迹
 */
public record ModeTrajectoryResult(
        String zoneCode,
        String currentMode,
        int currentVersion,
        List<SwitchItem> switches
) {

    /** 单次工况切换轨迹。 */
    public record SwitchItem(
            Long candidateId,
            String fromMode,
            String toMode,
            int zoneVersion,
            LocalDateTime tideTime,
            LocalDateTime switchTime
    ) {
    }
}
