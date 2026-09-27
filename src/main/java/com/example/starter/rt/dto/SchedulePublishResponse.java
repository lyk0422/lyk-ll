package com.example.starter.rt.dto;

import java.util.List;

/**
 * 连续分次批量排程发布结果：计划版本、等级、设备与窗口均已冻结。
 *
 * @param scheduleId    排程批次ID
 * @param patientId     患者ID
 * @param planCode      计划业务编码
 * @param planVersionId 冻结的计划版本ID
 * @param versionNo     冻结的计划版本号
 * @param level         冻结的复杂度等级：1/2/3
 * @param deviceId      冻结的设备ID
 * @param items         占用项（含冻结窗口）
 * @param version       批次版本号（发布为1）
 * @param requestId     发布幂等键
 * @param replayed      是否为同键同参重放
 */
public record SchedulePublishResponse(
        Long scheduleId,
        String patientId,
        String planCode,
        Long planVersionId,
        Integer versionNo,
        Integer level,
        String deviceId,
        List<ScheduleItemView> items,
        Integer version,
        String requestId,
        boolean replayed) {
}
