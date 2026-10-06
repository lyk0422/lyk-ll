package com.example.starter.repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.EventRow;
import com.example.starter.domain.EventStatus;

/**
 * 干扰事件数据访问。
 */
@Repository
public class EventRepository {

    private static final RowMapper<EventRow> MAPPER = (rs, n) -> new EventRow(
            rs.getLong("id"),
            rs.getBigDecimal("frequency_mhz"),
            EventStatus.valueOf(rs.getString("status")),
            rs.getBoolean("escalated"),
            rs.getTimestamp("escalated_at") == null ? null : rs.getTimestamp("escalated_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant(),
            rs.getString("reviewer_id"),
            rs.getString("review_conclusion"),
            rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(),
            rs.getString("closed_by"));

    private static final String COLUMNS =
            "id, frequency_mhz, status, escalated, escalated_at, created_at, reviewed_at,"
                    + " reviewer_id, review_conclusion, closed_at, closed_by";

    private final JdbcTemplate jdbc;

    public EventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(BigDecimal frequencyMhz, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO interference_event (frequency_mhz, status, escalated, created_at)"
                            + " VALUES (?, 'OPEN', FALSE, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setBigDecimal(1, frequencyMhz);
            ps.setTimestamp(2, Timestamp.from(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<EventRow> findById(long id) {
        List<EventRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM interference_event WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 是否存在影响该发射机（经 transmitter_channel 关联频率）且未结案的事件。
     */
    public boolean existsUnclosedForTransmitter(long transmitterId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interference_event e"
                        + " JOIN transmitter_channel tc ON e.frequency_mhz = tc.frequency_mhz"
                        + " WHERE tc.transmitter_id = ? AND e.status IN ('OPEN', 'CONFIRMED')",
                Integer.class, transmitterId);
        return count != null && count > 0;
    }

    /**
     * 最严格限值来源：提供该限值的事件 id 与限值（单位 W）。
     */
    public record StrictestLimit(long eventId, int limitWatts) {
    }

    /**
     * 该发射机（经 transmitter_channel 关联频率）所有 CONFIRMED 事件的保护限值中的
     * 最严格值（最小功率）及其来源事件；无则 empty。
     */
    public Optional<StrictestLimit> findStrictestLimitForTransmitter(long transmitterId) {
        List<StrictestLimit> rows = jdbc.query(
                "SELECT e.id, p.max_power_watts FROM interference_event e"
                        + " JOIN transmitter_channel tc ON e.frequency_mhz = tc.frequency_mhz"
                        + " JOIN protection_limit p ON p.frequency_mhz = e.frequency_mhz"
                        + " WHERE tc.transmitter_id = ? AND e.status = 'CONFIRMED'"
                        + " ORDER BY p.max_power_watts ASC, e.id ASC LIMIT 1",
                (rs, n) -> new StrictestLimit(rs.getLong(1), rs.getInt(2)),
                transmitterId);
        return rows.stream().findFirst();
    }

    /**
     * 记录初查结论（OPEN -> CONFIRMED / REJECTED）。
     */
    public void applyReview(long eventId, EventStatus newStatus, String reviewerId,
            String conclusion, Instant reviewedAt) {
        jdbc.update(
                "UPDATE interference_event SET status = ?, reviewer_id = ?, review_conclusion = ?,"
                        + " reviewed_at = ? WHERE id = ?",
                newStatus.name(), reviewerId, conclusion, Timestamp.from(reviewedAt), eventId);
    }

    public void applyClose(long eventId, String closedBy, Instant closedAt) {
        jdbc.update(
                "UPDATE interference_event SET status = 'CLOSED', closed_by = ?, closed_at = ? WHERE id = ?",
                closedBy, Timestamp.from(closedAt), eventId);
    }

    /**
     * 将创建满 24 小时（UTC，恰满计入）仍未初查的 OPEN 事件升级为督办，返回升级的事件 id。
     */
    public List<Long> escalateOverdue(Instant deadline, Instant escalatedAt) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM interference_event WHERE status = 'OPEN' AND escalated = FALSE"
                        + " AND created_at <= ? ORDER BY id",
                (rs, n) -> rs.getLong(1),
                Timestamp.from(deadline));
        for (Long id : ids) {
            jdbc.update(
                    "UPDATE interference_event SET escalated = TRUE, escalated_at = ? WHERE id = ?",
                    Timestamp.from(escalatedAt), id);
        }
        return ids;
    }
}
