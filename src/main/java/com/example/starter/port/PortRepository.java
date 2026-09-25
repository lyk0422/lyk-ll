package com.example.starter.port;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 携转流程、在途守卫、生效记录与事件的数据访问。
 *
 * <p>时间统一以 UTC 的 {@link LocalDateTime} 落库与读取，避免 JVM 时区影响；
 * 状态流转全部使用带状态前置条件的 UPDATE，保证并发下只有一个事务能完成终态切换。
 */
@Repository
public class PortRepository {

    private static final String FLOW_COLUMNS =
            "id, request_id, fingerprint, msisdn, donor_operator, recipient_operator, status,"
                    + " auth_code, auth_code_issued_at, auth_code_expires_at,"
                    + " effective_at, cancelled_at, cancel_reason, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public PortRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新流程（状态 APPLIED），返回生成的主键。
     */
    public long insertFlow(String requestId, String fingerprint, String msisdn,
                           String donorOperator, String recipientOperator, Instant now) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO port_request (request_id, fingerprint, msisdn, donor_operator,"
                            + " recipient_operator, status, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, 'APPLIED', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, msisdn);
            ps.setString(4, donorOperator);
            ps.setString(5, recipientOperator);
            ps.setObject(6, utc(now));
            ps.setObject(7, utc(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按主键查询流程。
     */
    public Optional<PortFlow> findById(long id) {
        return jdbc.query("SELECT " + FLOW_COLUMNS + " FROM port_request WHERE id = ?",
                (rs, rowNum) -> mapFlow(rs), id).stream().findFirst();
    }

    /**
     * 按申请幂等键查询流程。
     */
    public Optional<PortFlow> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + FLOW_COLUMNS + " FROM port_request WHERE request_id = ?",
                (rs, rowNum) -> mapFlow(rs), requestId).stream().findFirst();
    }

    /**
     * 尝试为号码占位在途名额；号码已在途时返回 false（唯一约束冲突）。
     */
    public boolean tryInsertInflight(String msisdn, long portRequestId, Instant now) {
        try {
            jdbc.update("INSERT INTO port_inflight (msisdn, port_request_id, created_at) VALUES (?, ?, ?)",
                    msisdn, portRequestId, utc(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 释放号码的在途名额（流程进入终态时调用）。
     */
    public void deleteInflight(String msisdn) {
        jdbc.update("DELETE FROM port_inflight WHERE msisdn = ?", msisdn);
    }

    /**
     * 签发授权码：仅 APPLIED 状态可推进，返回受影响行数（0 表示状态已变化）。
     */
    public int issueAuthCode(long id, String authCode, Instant issuedAt, Instant expiresAt, Instant now) {
        return jdbc.update(
                "UPDATE port_request SET status = 'CODE_ISSUED', auth_code = ?,"
                        + " auth_code_issued_at = ?, auth_code_expires_at = ?, updated_at = ?"
                        + " WHERE id = ? AND status = 'APPLIED'",
                authCode, utc(issuedAt), utc(expiresAt), utc(now), id);
    }

    /**
     * 携出方确认：仅 CODE_ISSUED 状态可推进。
     */
    public int confirm(long id, Instant now) {
        return jdbc.update(
                "UPDATE port_request SET status = 'CONFIRMED', updated_at = ?"
                        + " WHERE id = ? AND status = 'CODE_ISSUED'",
                utc(now), id);
    }

    /**
     * 生效：仅 CONFIRMED 状态可推进，同时作废授权码。
     */
    public int markEffective(long id, Instant now) {
        return jdbc.update(
                "UPDATE port_request SET status = 'EFFECTIVE', effective_at = ?,"
                        + " auth_code = NULL, updated_at = ?"
                        + " WHERE id = ? AND status = 'CONFIRMED'",
                utc(now), utc(now), id);
    }

    /**
     * 撤销：仅生效前的在途状态可推进，与生效并发时只有一个能成功。
     */
    public int cancel(long id, String reason, Instant now) {
        return jdbc.update(
                "UPDATE port_request SET status = 'CANCELLED', cancelled_at = ?, cancel_reason = ?,"
                        + " updated_at = ?"
                        + " WHERE id = ? AND status IN ('APPLIED', 'CODE_ISSUED', 'CONFIRMED')",
                utc(now), reason, utc(now), id);
    }

    /**
     * 追加流程事件。
     */
    public void insertEvent(long portRequestId, String eventType, String detail, Instant now) {
        jdbc.update("INSERT INTO port_event (port_request_id, event_type, detail, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                portRequestId, eventType, detail, utc(now));
    }

    /**
     * 按发生顺序查询流程事件。
     */
    public List<PortEvent> listEvents(long portRequestId) {
        return jdbc.query(
                "SELECT id, port_request_id, event_type, detail, created_at"
                        + " FROM port_event WHERE port_request_id = ? ORDER BY id",
                (rs, rowNum) -> new PortEvent(
                        rs.getLong("id"),
                        rs.getLong("port_request_id"),
                        rs.getString("event_type"),
                        rs.getString("detail"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                portRequestId);
    }

    /**
     * 插入生效记录；唯一约束冲突（同流程或同 requestId 已存在）时返回空。
     */
    public Optional<Long> insertEffectiveness(long portRequestId, String requestId,
                                              String fingerprint, String msisdn, Instant effectiveAt) {
        try {
            var keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement(
                        "INSERT INTO effectiveness_record"
                                + " (port_request_id, request_id, fingerprint, msisdn, effective_at)"
                                + " VALUES (?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, portRequestId);
                ps.setString(2, requestId);
                ps.setString(3, fingerprint);
                ps.setString(4, msisdn);
                ps.setObject(5, utc(effectiveAt));
                return ps;
            }, keyHolder);
            return Optional.of(keyHolder.getKey().longValue());
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    /**
     * 按广播幂等键查询生效记录。
     */
    public Optional<EffectivenessRecord> findEffectivenessByRequestId(String requestId) {
        return jdbc.query(
                "SELECT id, port_request_id, request_id, fingerprint, msisdn, effective_at"
                        + " FROM effectiveness_record WHERE request_id = ?",
                (rs, rowNum) -> mapEffectiveness(rs), requestId).stream().findFirst();
    }

    /**
     * 按流程主键查询生效记录。
     */
    public Optional<EffectivenessRecord> findEffectivenessByFlowId(long portRequestId) {
        return jdbc.query(
                "SELECT id, port_request_id, request_id, fingerprint, msisdn, effective_at"
                        + " FROM effectiveness_record WHERE port_request_id = ?",
                (rs, rowNum) -> mapEffectiveness(rs), portRequestId).stream().findFirst();
    }

    private PortFlow mapFlow(ResultSet rs) throws SQLException {
        return new PortFlow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getString("msisdn"),
                rs.getString("donor_operator"),
                rs.getString("recipient_operator"),
                PortStatus.valueOf(rs.getString("status")),
                rs.getString("auth_code"),
                toInstant(rs, "auth_code_issued_at"),
                toInstant(rs, "auth_code_expires_at"),
                toInstant(rs, "effective_at"),
                toInstant(rs, "cancelled_at"),
                rs.getString("cancel_reason"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private EffectivenessRecord mapEffectiveness(ResultSet rs) throws SQLException {
        return new EffectivenessRecord(
                rs.getLong("id"),
                rs.getLong("port_request_id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getString("msisdn"),
                toInstant(rs, "effective_at"));
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        var ldt = rs.getObject(column, LocalDateTime.class);
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
