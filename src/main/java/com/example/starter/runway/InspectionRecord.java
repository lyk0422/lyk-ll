package com.example.starter.runway;

import java.time.Instant;

/**
 * 巡检记录行：对应 inspection_record 表。低能见度单人巡检记录不生效（不入库）。
 *
 * @param id                   巡检记录主键
 * @param eventId              所属事件ID
 * @param startedAt            巡检开始时间（UTC）
 * @param inspectorCount       巡检人数
 * @param late                 是否迟延（恰满15分钟算迟延）
 * @param recordedAfterArchive 是否归档后迟到登记
 * @param createdAt            记录创建时间（UTC）
 */
public record InspectionRecord(
        long id,
        long eventId,
        Instant startedAt,
        int inspectorCount,
        boolean late,
        boolean recordedAfterArchive,
        Instant createdAt) {
}
