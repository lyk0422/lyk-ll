package com.example.starter.pump.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 强排候选确认结果（成功切换后返回）。
 *
 * @param sequenceId     新生效序列ID
 * @param zoneCode       流域分区代码
 * @param direction      切换方向 ENTER/EXIT
 * @param state          序列状态 ACTIVE
 * @param tideVersion    固定潮位版本
 * @param regimeVersion  生效时工况配置版本
 * @param supersededSequenceId 被终结的旧强排序列ID（首次进入为null）
 * @param tasks          原子写出的各站启停任务
 * @param replayed       是否同键同参重放
 */
public record CandidateConfirmResult(
        Long sequenceId,
        String zoneCode,
        String direction,
        String state,
        Long tideVersion,
        Integer regimeVersion,
        Long supersededSequenceId,
        List<TaskItem> tasks,
        boolean replayed
) {
    /** 确认后原子写出的单站任务。 */
    public record TaskItem(String stationCode, String action, OffsetDateTime taskTime) {
    }
}
