package com.example.starter.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.InterferenceEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 干扰事件的数据访问。
 */
@Repository
public class EventRepository {

    private static final RowMapper<InterferenceEvent> MAPPER = (rs, n) -> new InterferenceEvent(
            rs.getLong("id"),
            rs.getBigDecimal("frequency_mhz"),
            rs.getLong("transmitter_id"),
            rs.getString("status"),
            rs.getString("conclusion"),
            rs.getObject("confirmed_limit_w", Integer.class),
            rs.getLong("trigger_complaint_id"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            toLocal(rs.getTimestamp("concluded_at")),
            toLocal(rs.getTimestamp("closed_at")),
            rs.getString("investigate_request_id"),
            rs.getString("investigate_fingerprint"));

    private final JdbcTemplate jdbc;

    public EventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /**
     * 创建干扰事件，初始状态 OPEN。
     *
     * @return 新事件主键
     */
    public long insert(BigDecimal frequencyMhz, long transmitterId, long triggerComplaintId, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO interference_event (frequency_mhz, transmitter_id, status, trigger_complaint_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setBigDecimal(1, frequencyMhz);
            ps.setLong(2, transmitterId);
            ps.setString(3, InterferenceEvent.STATUS_OPEN);
            ps.setLong(4, triggerComplaintId);
            ps.setTimestamp(5, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<InterferenceEvent> findById(long id) {
        return jdbc.query("SELECT * FROM interference_event WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * 查询事件并加行锁，串行化同一事件的初查处置。
     */
    public Optional<InterferenceEvent> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM interference_event WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public List<InterferenceEvent> findAll() {
        return jdbc.query("SELECT * FROM interference_event ORDER BY id", MAPPER);
    }

    /**
     * 是否存在该频率的未结事件（OPEN 或 CONFIRMED）。
     */
    public boolean existsUnresolvedByFrequency(BigDecimal frequencyMhz) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interference_event WHERE frequency_mhz = ? AND status IN ('OPEN', 'CONFIRMED')",
                Integer.class, frequencyMhz);
        return count != null && count > 0;
    }

    /**
     * 该发射机的未结事件数（OPEN 或 CONFIRMED）。
     */
    public int countUnresolvedByTransmitter(long transmitterId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interference_event WHERE transmitter_id = ? AND status IN ('OPEN', 'CONFIRMED')",
                Integer.class, transmitterId);
        return count == null ? 0 : count;
    }

    /**
     * 该发射机全部未结且已确认事件的限值快照，用于重算最严格限值。
     */
    public List<Integer> findConfirmedLimitsByTransmitter(long transmitterId) {
        return jdbc.query(
                "SELECT confirmed_limit_w FROM interference_event"
                        + " WHERE transmitter_id = ? AND status = 'CONFIRMED' AND confirmed_limit_w IS NOT NULL",
                (rs, n) -> rs.getInt("confirmed_limit_w"), transmitterId);
    }

    /**
     * 落库初查结论：结论、状态、限值快照与幂等指纹一次更新。
     */
    public void completeInvestigation(long id, String conclusion, String status, Integer confirmedLimitW,
                                      LocalDateTime concludedAt, LocalDateTime closedAt,
                                      String requestId, String fingerprint) {
        jdbc.update("UPDATE interference_event SET conclusion = ?, status = ?, confirmed_limit_w = ?,"
                        + " concluded_at = ?, closed_at = ?, investigate_request_id = ?, investigate_fingerprint = ?"
                        + " WHERE id = ?",
                conclusion, status, confirmedLimitW,
                concludedAt == null ? null : Timestamp.valueOf(concludedAt),
                closedAt == null ? null : Timestamp.valueOf(closedAt),
                requestId, fingerprint, id);
    }

    public void close(long id, LocalDateTime closedAt) {
        jdbc.update("UPDATE interference_event SET status = ?, closed_at = ? WHERE id = ?",
                InterferenceEvent.STATUS_CLOSED, Timestamp.valueOf(closedAt), id);
    }
}
