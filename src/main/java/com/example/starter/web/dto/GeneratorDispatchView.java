package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发电车调度单视图。
 *
 * @param dispatchId       调度单ID
 * @param workOrderId      关联工单ID
 * @param workOrderVersion 调度时冻结的工单版本
 * @param totalLoadKw      冻结的合计保电负荷，单位kW
 * @param status           DISPATCHED/ARRIVAL_TIMEOUT/SUPPLYING/REASSIGNED/CANCELLED
 * @param version          调度单版本，从1开始
 * @param predecessorId    改派来源调度单ID，空表示首派
 * @param dispatchedAt     下发时间，30分钟到达时限起算点
 * @param suppliedAt       全部到场确认供电时间，空表示未供电
 * @param trucks           组合车辆及到场情况
 * @param loads            冻结的重要用户负荷清单
 * @param replayed         是否幂等重放
 */
public record GeneratorDispatchView(
        long dispatchId,
        long workOrderId,
        int workOrderVersion,
        int totalLoadKw,
        String status,
        int version,
        Long predecessorId,
        LocalDateTime dispatchedAt,
        LocalDateTime suppliedAt,
        List<DispatchTruckView> trucks,
        List<DispatchLoadView> loads,
        boolean replayed) implements ReplayableResponse<GeneratorDispatchView> {

    @Override
    public GeneratorDispatchView asReplayed() {
        return new GeneratorDispatchView(dispatchId, workOrderId, workOrderVersion, totalLoadKw,
                status, version, predecessorId, dispatchedAt, suppliedAt, trucks, loads, true);
    }
}
