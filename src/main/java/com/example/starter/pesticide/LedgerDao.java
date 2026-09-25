package com.example.starter.pesticide;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 台账数据访问：准入放行、监督处置、留样、产地预警与迟到事件。
 */
@Repository
public class LedgerDao {

    /**
     * 准入放行记录行。
     */
    public record AdmissionRow(long id, long batchId, LocalDateTime releasedAt) {
    }

    /**
     * 留样记录行。
     */
    public record RetentionRow(long id, long batchId, int retainHours, LocalDateTime retainUntil,
                               LocalDateTime createdAt) {
    }

    /**
     * 产地预警台账行。
     */
    public record AlertRow(long id, String origin, String variety, LocalDate alertDate,
                           int triggerCount, String status, LocalDateTime createdAt) {
    }

    /**
     * 迟到事件行。
     */
    public record LateEventRow(long id, long batchId, String eventType, String payload,
                               LocalDateTime createdAt) {
    }

    private static final RowMapper<AdmissionRow> ADMISSION_MAPPER = (rs, n) -> new AdmissionRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getTimestamp("released_at").toLocalDateTime());

    private static final RowMapper<RetentionRow> RETENTION_MAPPER = (rs, n) -> new RetentionRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getInt("retain_hours"),
            rs.getTimestamp("retain_until").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<AlertRow> ALERT_MAPPER = (rs, n) -> new AlertRow(
            rs.getLong("id"), rs.getString("origin"), rs.getString("variety"),
            rs.getDate("alert_date").toLocalDate(), rs.getInt("trigger_count"),
            rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<LateEventRow> LATE_EVENT_MAPPER = (rs, n) -> new LateEventRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("event_type"),
            rs.getString("payload"), rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public LedgerDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记准入放行；每批次至多一条，重复时抛出 DuplicateKeyException。
     */
    public long insertAdmission(long batchId, LocalDateTime releasedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO admission (batch_id, released_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setTimestamp(2, Timestamp.valueOf(releasedAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询批次放行记录。
     */
    public Optional<AdmissionRow> findAdmission(long batchId) {
        List<AdmissionRow> rows = jdbc.query("SELECT * FROM admission WHERE batch_id = ?",
                ADMISSION_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /**
     * 登记监督处置结案；每批次至多一条。
     */
    public long insertDisposal(long batchId, String reason, LocalDateTime disposedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO disposal (batch_id, reason, disposed_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, reason);
            ps.setTimestamp(3, Timestamp.valueOf(disposedAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 登记留样（处置结案批次留样 48 小时）。
     */
    public long insertRetention(long batchId, int retainHours, LocalDateTime retainUntil, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO retention (batch_id, retain_hours, retain_until, created_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setInt(2, retainHours);
            ps.setTimestamp(3, Timestamp.valueOf(retainUntil));
            ps.setTimestamp(4, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询批次留样记录。
     */
    public Optional<RetentionRow> findRetention(long batchId) {
        List<RetentionRow> rows = jdbc.query("SELECT * FROM retention WHERE batch_id = ?",
                RETENTION_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /**
     * 登记产地预警；同产地+品种+日期唯一，重复时抛出 DuplicateKeyException。
     */
    public long insertAlert(String origin, String variety, LocalDate alertDate, int triggerCount,
                            LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO origin_alert (origin, variety, alert_date, trigger_count, status, created_at)"
                            + " VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, origin);
            ps.setString(2, variety);
            ps.setDate(3, Date.valueOf(alertDate));
            ps.setInt(4, triggerCount);
            ps.setTimestamp(5, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询产地+品种+日期是否已有预警。
     */
    public boolean alertExists(String origin, String variety, LocalDate alertDate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM origin_alert WHERE origin = ? AND variety = ? AND alert_date = ?",
                Integer.class, origin, variety, Date.valueOf(alertDate));
        return count != null && count > 0;
    }

    /**
     * 判断产地+品种是否存在生效中的预警（用于后续批次加严抽检）。
     */
    public boolean hasActiveAlert(String origin, String variety) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM origin_alert WHERE origin = ? AND variety = ? AND status = 'ACTIVE'",
                Integer.class, origin, variety);
        return count != null && count > 0;
    }

    /**
     * 查询全部预警台账。
     */
    public List<AlertRow> findAllAlerts() {
        return jdbc.query("SELECT * FROM origin_alert ORDER BY id", ALERT_MAPPER);
    }

    /**
     * 登记迟到事件。
     */
    public long insertLateEvent(long batchId, String eventType, String payload, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO late_event (batch_id, event_type, payload, created_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, eventType);
            ps.setString(3, payload);
            ps.setTimestamp(4, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询批次迟到事件。
     */
    public List<LateEventRow> findLateEvents(long batchId) {
        return jdbc.query("SELECT * FROM late_event WHERE batch_id = ? ORDER BY id",
                LATE_EVENT_MAPPER, batchId);
    }
}
