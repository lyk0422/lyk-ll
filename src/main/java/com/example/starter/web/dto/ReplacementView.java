package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 热备接替单视图：冻结的负荷与工单/调度版本、旧车与热备新车、切换确认人与前后容量证据。
 *
 * @param id              接替单ID
 * @param dispatchId      原SUPPLYING调度单ID
 * @param workOrderId     工单ID
 * @param oldTruckId      被接替的供电旧车ID
 * @param orderVersion    冻结的工单版本号
 * @param dispatchVersion 冻结的原调度单版本号
 * @param status          状态：STANDBY/SWITCHED/WITHDRAWN/CLOSED
 * @param version         接替单当前版本号
 * @param totalLoadKw     冻结合计负荷，单位kW
 * @param oldCapacityKw   切换前旧车容量证据，单位kW
 * @param newCapacityKw   切换后新车容量合计证据，单位kW，空表示未切换
 * @param trucks          热备新车及到场时刻
 * @param confirmations   已确认切换的合成操作员（按确认先后）
 * @param createdAt       接替单创建时刻（本地时间）
 * @param switchedAt      切换完成时刻，空表示未切换
 */
public record ReplacementView(long id, long dispatchId, long workOrderId, long oldTruckId,
        int orderVersion, int dispatchVersion, String status, int version, int totalLoadKw,
        int oldCapacityKw, Integer newCapacityKw, List<ReplacementTruckView> trucks,
        List<String> confirmations, LocalDateTime createdAt, LocalDateTime switchedAt) {
}
