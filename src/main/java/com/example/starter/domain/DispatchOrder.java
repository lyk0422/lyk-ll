package com.example.starter.domain;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 调度单。
 *
 * <p>生命周期：PENDING -> DISPATCHED -> GENERATING；确认超时升级 GENERATION_ABNORMAL 并改派
 * （新单 DISPATCHED）；市电恢复转 RELEASED；人工结案为 CLOSED。RELEASED/CLOSED 为终态。
 *
 * @param id                   主键
 * @param requestId            调度请求幂等键
 * @param stationId            被保障基站 ID
 * @param eventId              关联停电事件 ID
 * @param genId                当前服务油机 ID；改派后指向新油机
 * @param status               单据状态
 * @param arrivalDeadlineUtc   油机预计到达时限，UTC
 * @param dispatchDeadline     派单后到位启动截止时刻（派单+30 分钟），UTC；恰满判超时
 * @param confirmedAt          发电确认回传时刻，UTC；未确认为 null
 * @param abnormalAt           异常升级时刻，UTC；未异常为 null
 * @param releasedAt           解除时刻，UTC；未解除为 null
 * @param closedAt             结案时刻，UTC；未结案为 null
 * @param createdAt            创建时刻，UTC
 * @param updatedAt            最近更新时刻，UTC
 */
public record DispatchOrder(
        Long id,
        String requestId,
        Long stationId,
        Long eventId,
        Long genId,
        OrderStatus status,
        Instant arrivalDeadlineUtc,
        Instant dispatchDeadline,
        Instant confirmedAt,
        Instant abnormalAt,
        Instant releasedAt,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public boolean isTerminal() {
        return status == OrderStatus.RELEASED || status == OrderStatus.CLOSED;
    }

    public static RowMapper<DispatchOrder> rowMapper() {
        return (ResultSet rs, int rowNum) -> new DispatchOrder(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getLong("station_id"),
                rs.getLong("event_id"),
                nullableLong(rs, "gen_id"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("arrival_deadline_utc").toInstant(),
                rs.getTimestamp("dispatch_deadline").toInstant(),
                nullableInstant(rs, "confirmed_at"),
                nullableInstant(rs, "abnormal_at"),
                nullableInstant(rs, "released_at"),
                nullableInstant(rs, "closed_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static Long nullableLong(ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
