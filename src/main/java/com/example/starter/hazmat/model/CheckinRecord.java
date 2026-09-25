package com.example.starter.hazmat.model;

import com.example.starter.hazmat.domain.CheckinResult;
import java.time.LocalDateTime;

/**
 * 押运打卡记录。
 *
 * @param id          主键
 * @param requestId   幂等请求标识
 * @param fingerprint 幂等指纹
 * @param waybillId   所属运单
 * @param waypointSeq 途经点序号
 * @param checkinTime 打卡时刻
 * @param result      打卡结果
 * @param message     结果说明，可为 null
 * @param createdAt   记录创建时刻
 */
public record CheckinRecord(
        long id,
        String requestId,
        String fingerprint,
        long waybillId,
        int generation,
        int waypointSeq,
        LocalDateTime checkinTime,
        CheckinResult result,
        String message,
        LocalDateTime createdAt) {
}
