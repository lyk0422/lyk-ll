package com.example.starter.hazmat.model;

import com.example.starter.hazmat.domain.WaypointStatus;
import java.time.LocalDateTime;

/**
 * 运单途经点实例。
 *
 * @param id              主键
 * @param waybillId       所属运单
 * @param seq             途经点序号
 * @param plannedPassTime 计划通过时刻
 * @param checkinTime     实际打卡时刻，未打卡为 null
 * @param status          途经点状态
 */
public record WaybillWaypoint(
        long id,
        long waybillId,
        int generation,
        int seq,
        LocalDateTime plannedPassTime,
        LocalDateTime checkinTime,
        WaypointStatus status) {
}
