package com.example.starter.rt.dto;

import java.util.List;

/**
 * 排程批次视图。
 *
 * @param scheduleId    排程批次ID
 * @param patientId     患者ID
 * @param planCode      计划业务编码
 * @param planVersionId 发布时冻结的计划版本ID
 * @param versionNo     发布时冻结的计划版本号
 * @param level         复杂度等级（1-3）
 * @param deviceId      占用设备ID
 * @param version       排程版本（从1开始，成功变更加1）
 * @param status        状态：ACTIVE/CANCELLED
 * @param requestId     本次写操作幂等键
 * @param replayed      是否幂等重放
 * @param items         排程项集合
 */
public record ScheduleResponse(
        long scheduleId,
        String patientId,
        String planCode,
        long planVersionId,
        Integer versionNo,
        int level,
        String deviceId,
        int version,
        String status,
        String requestId,
        boolean replayed,
        List<ScheduleItemView> items) {
}
