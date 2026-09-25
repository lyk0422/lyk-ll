package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

import com.example.starter.hazmat.domain.WaypointState;

/**
 * 某次路线核准下的途经点实例（含计划通过时刻与监控状态）。
 *
 * @param id           主键
 * @param approvalId   路线核准记录 ID
 * @param shipmentId   运单 ID
 * @param seq          途经点序号（运单内同一路线版本内从 0 起）
 * @param name         途经点名称
 * @param plannedTime  计划通过时刻（Asia/Shanghai）
 * @param state        监控状态
 * @param checkedTime  实际打卡时刻；未打卡为 null
 */
public record ApprovalWaypoint(
        long id,
        long approvalId,
        long shipmentId,
        int seq,
        String name,
        LocalDateTime plannedTime,
        WaypointState state,
        LocalDateTime checkedTime) {

    /** 超时阈值：计划通过时刻之后 60 分钟整（恰满 60 分钟即判超时）。 */
    public LocalDateTime deadline() {
        return plannedTime.plusMinutes(60);
    }
}
