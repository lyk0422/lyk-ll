package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

import com.example.starter.hazmat.domain.ReservationStatus;

/**
 * 人员资格预约：运单 + 接力段 + 接班员的班段占用记录，校验与占用同一事务提交。
 *
 * @param id                   主键
 * @param shipmentId           运单 ID
 * @param segmentId            接力段 ID（须属于该运单，跨运单接力点拒绝）
 * @param escortId             接班员编号
 * @param qualificationId      预约校验依据的资格记录 ID（证据）
 * @param qualificationVersion 预约校验时的资格版本号（证据）
 * @param shiftStartUtc        班段起点（UTC），半开区间[start,end)
 * @param shiftEndUtc          班段终点（UTC），半开区间[start,end)，实际接班时刻恰等于终点拒绝
 * @param status               预约状态
 * @param versionNo            乐观锁版本：从 1 起，修改用 expectedVersion，成功加 1，过期 409
 * @param requestId            创建请求的幂等键
 * @param createdAt            创建时刻（Asia/Shanghai）
 * @param updatedAt            最近修改时刻（Asia/Shanghai）
 */
public record EscortReservation(
        long id,
        long shipmentId,
        long segmentId,
        String escortId,
        long qualificationId,
        int qualificationVersion,
        LocalDateTime shiftStartUtc,
        LocalDateTime shiftEndUtc,
        ReservationStatus status,
        int versionNo,
        String requestId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
