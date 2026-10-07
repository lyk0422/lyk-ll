package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 人员资格预约：启用人员管控的接力段须先预约（运单+接力点+接班员+班段）。
 *
 * @param id              主键
 * @param shipmentId      运单 ID
 * @param segmentId       接力段 ID，须属于本运单（跨运单接力点拒绝）
 * @param escortId        接班员编号
 * @param qualificationId 预约时使用的资格 ID，受控段 IN 确认时重查未撤销
 * @param shiftStart      班段起点（UTC，半开区间 [start,end)）
 * @param shiftEnd        班段终点（UTC，半开区间 [start,end)），实际接班时刻恰到此点拒绝
 * @param status          ACTIVE 生效中 / RELEASED 已释放
 * @param versionNo       预约版本号：从 1 起，每次修改（含释放）加 1
 * @param createdAt       创建时刻
 * @param releasedAt      释放时刻；未释放为 null
 */
public record EscortReservation(
        long id,
        long shipmentId,
        long segmentId,
        String escortId,
        long qualificationId,
        LocalDateTime shiftStart,
        LocalDateTime shiftEnd,
        String status,
        int versionNo,
        LocalDateTime createdAt,
        LocalDateTime releasedAt) {

    /** 预约状态：生效中。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 预约状态：已释放（撤销预约、资格撤销连带释放或运单终结释放）。 */
    public static final String STATUS_RELEASED = "RELEASED";
}
