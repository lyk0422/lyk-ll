package com.example.starter.sync.repo;

import com.example.starter.sync.SensorType;

/**
 * 同步事件记录，对应 sync_event 表的一行。可空字段为 NULL 时表示该事件不涉及此属性。
 */
public record SyncEventRow(
        long seq,
        String eventType,
        SensorType sensorType,
        String messageId,
        Long targetTs,
        Long rawTs,
        Integer driftVersion,
        Long offsetMs,
        Long effectiveFrom,
        String reason,
        long createdAt) {

    /**
     * 事件类型常量。
     */
    public static final String MESSAGE_ACCEPTED = "MESSAGE_ACCEPTED";
    public static final String MESSAGE_DUPLICATE = "MESSAGE_DUPLICATE";
    public static final String MESSAGE_DISCARDED = "MESSAGE_DISCARDED";
    public static final String DRIFT_UPDATED = "DRIFT_UPDATED";
    public static final String FRAME_COMMITTED = "FRAME_COMMITTED";
}
