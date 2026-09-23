package com.example.starter.coldchain.dto;

import com.example.starter.coldchain.domain.SegmentStatus;

import java.time.LocalDateTime;

/**
 * 交接结果（同 requestId 同参重放返回首次冻结的结果）。
 *
 * @param requestId      幂等键
 * @param shipmentId     货件标识
 * @param closedVersion  被关闭的旧段版本号
 * @param status         旧段判定结果：CLEAR/EXCURSION/INCOMPLETE
 * @param newVersion     新打开段的版本号
 * @param receiver       新段保管方
 * @param startTime      新段开始时间（即交接时间）
 * @param replayed       是否为幂等重放
 */
public record HandoverResponse(
        String requestId,
        String shipmentId,
        int closedVersion,
        SegmentStatus status,
        int newVersion,
        String receiver,
        LocalDateTime startTime,
        boolean replayed
) {
}
