package com.example.starter.repo;

import com.example.starter.domain.ReopenRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 复开申请与双岗确认数据访问。
 */
@Repository
public class ReopenRepository {

    /** 复开申请行记录；status：PENDING 待确认 / APPROVED 已复开。 */
    public record ReopenRequestRow(long id, long eventId, String status, LocalDateTime requestedAt) {
    }

    private final JdbcTemplate jdbc;

    public ReopenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertRequest(long eventId, LocalDateTime requestedAt) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO reopen_request (event_id, status, requested_at) "
                    + "VALUES (?, 'PENDING', ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, eventId);
            ps.setTimestamp(2, Timestamp.valueOf(requestedAt));
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) {
            throw new IllegalStateException("复开申请写入未返回主键");
        }
        return key.longValue();
    }

    public Optional<ReopenRequestRow> findRequestByEventId(long eventId) {
        return jdbc.query("SELECT id, event_id, status, requested_at FROM reopen_request WHERE event_id = ?",
                (rs, n) -> mapRequest(rs), eventId).stream().findFirst();
    }

    public Optional<ReopenRequestRow> findRequestById(long id) {
        return jdbc.query("SELECT id, event_id, status, requested_at FROM reopen_request WHERE id = ?",
                (rs, n) -> mapRequest(rs), id).stream().findFirst();
    }

    public void markApproved(long id) {
        jdbc.update("UPDATE reopen_request SET status = 'APPROVED' WHERE id = ?", id);
    }

    /** 写入岗位确认；同岗位重复确认返回 false（唯一约束去重）。 */
    public boolean insertConfirmationIfAbsent(long reopenRequestId, ReopenRole role, LocalDateTime confirmedAt) {
        try {
            jdbc.update("INSERT INTO reopen_confirmation (reopen_request_id, role, confirmed_at) VALUES (?, ?, ?)",
                    reopenRequestId, role.name(), Timestamp.valueOf(confirmedAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 申请已确认的岗位列表。 */
    public List<ReopenRole> findConfirmedRoles(long reopenRequestId) {
        return jdbc.query("SELECT role FROM reopen_confirmation WHERE reopen_request_id = ? ORDER BY role",
                (rs, n) -> ReopenRole.valueOf(rs.getString("role")), reopenRequestId);
    }

    private ReopenRequestRow mapRequest(ResultSet rs) throws SQLException {
        return new ReopenRequestRow(rs.getLong("id"), rs.getLong("event_id"), rs.getString("status"),
                rs.getTimestamp("requested_at").toLocalDateTime());
    }
}
