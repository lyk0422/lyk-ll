package com.example.starter.web.dto;

/**
 * 线路视图。
 *
 * @param id                  线路 id
 * @param code                线路编码
 * @param name                线路名称
 * @param status              线路状态（风控原因）
 * @param activeEventId       进行中管制事件 id，无为 null
 * @param maintenanceBlocked  查询时刻是否处于检修封锁中
 * @param maintenanceOrderId  当前阻断的检修单 id，无为 null
 */
public record LineView(long id, String code, String name, String status, Long activeEventId,
                       boolean maintenanceBlocked, Long maintenanceOrderId) {
}
