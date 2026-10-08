package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 热备接替单视图：冻结的原调度版本与负荷、旧车与1至3辆热备新车及到场情况、
 * 双操作员确认名单、状态版本以及切换完成后的前后容量证据。
 *
 * @param id                接替单ID
 * @param dispatchId        被接替的调度单ID
 * @param workOrderId       工单ID
 * @param oldTruckId        被换下的旧车ID
 * @param feederId          新旧车共同馈线编号
 * @param status            状态：WAITING待切换/SWITCHED已切换/REVOKED已撤回/CANCELLED已取消
 * @param version           接替单当前版本号（从1开始）
 * @param frozenOrderVersion 冻结的原调度工单版本号
 * @param frozenLoadKw      冻结的合计负荷，单位kW
 * @param oldCapacityKw     旧车容量证据，单位kW
 * @param newCapacityKw     新车容量之和，单位kW
 * @param createdAt         申请创建时间（本地时间）
 * @param newTrucks         热备新车及到场时刻
 * @param confirmers        已确认切换的操作员名单
 * @param evidence          切换前后容量证据，未切换为空
 * @param switchedAt        原子切换时刻，空表示未切换
 */
public record ReplacementView(long id, long dispatchId, long workOrderId, long oldTruckId, String feederId,
        String status, int version, int frozenOrderVersion, int frozenLoadKw, int oldCapacityKw,
        int newCapacityKw, LocalDateTime createdAt, List<ReplacementTruckView> newTrucks,
        List<String> confirmers, ReplacementSwitchEvidence evidence, LocalDateTime switchedAt) {
}
