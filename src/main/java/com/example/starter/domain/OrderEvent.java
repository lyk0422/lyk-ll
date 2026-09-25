package com.example.starter.domain;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 调度单生命周期事件（含迟到发电确认，仅记事件不改结论）。
 *
 * @param id        主键
 * @param orderId   调度单 ID
 * @param eventType 事件类型
 * @param genId     关联油机 ID
 * @param eventAt   事件时刻，UTC
 * @param note      备注
 * @param createdAt 入库时刻，UTC
 */
public record OrderEvent(
        Long id,
        Long orderId,
        String eventType,
        Long genId,
        Instant eventAt,
        String note,
        Instant createdAt
) {

    public static final String TYPE_DISPATCHED = "DISPATCHED";
    public static final String TYPE_CONFIRMED = "CONFIRMED";
    public static final String TYPE_ABNORMAL = "ABNORMAL";
    public static final String TYPE_REASSIGNED = "REASSIGNED";
    public static final String TYPE_RELEASED = "RELEASED";
    public static final String TYPE_CLOSED = "CLOSED";
    public static final String TYPE_LATE_CONFIRM = "LATE_CONFIRM";

    public static RowMapper<OrderEvent> rowMapper() {
        return (ResultSet rs, int rowNum) -> {
            long genId = rs.getLong("gen_id");
            return new OrderEvent(
                    rs.getLong("id"),
                    rs.getLong("order_id"),
                    rs.getString("event_type"),
                    rs.wasNull() ? null : genId,
                    rs.getTimestamp("event_at").toInstant(),
                    rs.getString("note"),
                    rs.getTimestamp("created_at").toInstant()
            );
        };
    }
}
