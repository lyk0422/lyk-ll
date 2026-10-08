package com.example.starter.web.dto;

import java.util.List;

/**
 * 线路视图：分别呈现风速管制与检修封锁两类阻断原因。
 *
 * @param id                         线路 id
 * @param code                       线路编码
 * @param name                       线路名称
 * @param status                     风速管制线路状态：NORMAL / SLOW_STOP / EMERGENCY_STOP
 * @param activeEventId              进行中风控事件 id，无为 null
 * @param maintenanceBlocked         当前时刻是否存在生效检修封锁
 * @param activeMaintenanceOrderIds  当前生效检修单 id 列表（未解除的检修阻断留证）
 */
public record LineView(long id, String code, String name, String status, Long activeEventId,
                       boolean maintenanceBlocked, List<Long> activeMaintenanceOrderIds) {
}
