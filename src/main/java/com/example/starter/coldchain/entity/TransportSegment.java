package com.example.starter.coldchain.entity;

import com.example.starter.coldchain.domain.SegmentStatus;

import java.time.LocalDateTime;

/**
 * 运输段持久化对象。endTime/lastSequence 为 {@code null} 表示开放段。
 *
 * @param id             自增主键
 * @param shipmentId     货件标识
 * @param version        运输段版本号，货件内唯一
 * @param custodian      本段保管方
 * @param startTime      本段开始时间（Asia/Shanghai）
 * @param endTime        本段交接关闭时间；{@code null}=开放中
 * @param firstSequence  本段首个设备序号（含）
 * @param lastSequence   本段末个设备序号（含）；{@code null}=开放中
 * @param status         段状态
 */
public record TransportSegment(
        Long id,
        String shipmentId,
        int version,
        String custodian,
        LocalDateTime startTime,
        LocalDateTime endTime,
        long firstSequence,
        Long lastSequence,
        SegmentStatus status
) {
}
