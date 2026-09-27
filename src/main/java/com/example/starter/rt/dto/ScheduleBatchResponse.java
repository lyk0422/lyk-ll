package com.example.starter.rt.dto;

import java.time.Instant;
import java.util.List;

/**
 * 排程批次视图（发布/取消/查询共用）。
 *
 * @param batchId         排程批次ID
 * @param patientId       患者ID
 * @param planCode        计划业务编码
 * @param planVersionId   冻结的计划版本ID
 * @param versionNo       冻结的计划版本号
 * @param deviceId        冻结的设备ID
 * @param level           冻结的复杂度等级
 * @param requiredMinutes 冻结的单分次所需分钟数
 * @param status          批次状态：ACTIVE/COMPLETED/CANCELLED
 * @param batchVersion    批次乐观锁版本
 * @param requestId       发布幂等键
 * @param replayed        是否为同键同参重放
 * @param items           批内明细
 * @param createdAt       发布时间（UTC）
 */
public record ScheduleBatchResponse(
        Long batchId,
        String patientId,
        String planCode,
        Long planVersionId,
        Integer versionNo,
        String deviceId,
        Integer level,
        Integer requiredMinutes,
        String status,
        Integer batchVersion,
        String requestId,
        boolean replayed,
        List<ScheduleItemView> items,
        Instant createdAt) {
}
