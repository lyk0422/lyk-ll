package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发电车调度单视图：冻结的负荷清单与工单版本、组合车辆及到场情况、供电证据时刻。
 *
 * @param id              调度单ID
 * @param workOrderId     工单ID
 * @param orderVersion    冻结的工单版本号
 * @param status          状态：IN_PROGRESS/SUPPLYING/TIMED_OUT/REASSIGNED/CANCELLED
 * @param version         调度单当前版本号
 * @param totalLoadKw     冻结合计负荷，单位kW
 * @param totalCapacityKw 车总容量，单位kW
 * @param dispatchedAt    下发时刻（本地时间）
 * @param loads           冻结负荷清单
 * @param trucks          组合车辆及到场时刻
 * @param suppliedAt      全部到场供电时刻，空表示未供电
 * @param supersededById  改派生成的新调度单ID，空表示未被改派
 */
public record GeneratorDispatchView(long id, long workOrderId, int orderVersion, String status, int version,
        int totalLoadKw, int totalCapacityKw, LocalDateTime dispatchedAt, List<LoadItem> loads,
        List<DispatchTruckView> trucks, LocalDateTime suppliedAt, Long supersededById) {
}
