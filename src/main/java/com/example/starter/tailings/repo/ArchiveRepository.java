package com.example.starter.tailings.repo;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 复盘归档存取：pond_id 唯一约束保证归档终态。
 */
@Repository
public class ArchiveRepository {

    private final JdbcTemplate jdbc;

    public ArchiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 复盘归档行。
     *
     * @param id         主键
     * @param requestId  幂等请求ID
     * @param pondId     尾矿库ID
     * @param calcId     关联调洪演算ID
     * @param planId     关联泄流方案ID
     * @param commandId  关联泄流指令ID
     * @param status     ARCHIVED已归档(终态)
     * @param archivedAt 归档时刻
     */
    public record ArchiveRow(long id, String requestId, long pondId, Long calcId, Long planId,
                             Long commandId, String status, LocalDateTime archivedAt) {
    }

    public long insert(String requestId, long pondId, Long calcId, Long planId, Long commandId,
                       LocalDateTime archivedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO review_archive (request_id, pond_id, calc_id, plan_id, command_id, status, archived_at)"
                            + " VALUES (?, ?, ?, ?, ?, 'ARCHIVED', ?)",
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setLong(2, pondId);
            if (calcId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, calcId);
            }
            if (planId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, planId);
            }
            if (commandId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, commandId);
            }
            ps.setTimestamp(6, Timestamp.valueOf(archivedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ArchiveRow> findByPond(long pondId) {
        return jdbc.query(
                        "SELECT id, request_id, pond_id, calc_id, plan_id, command_id, status, archived_at"
                                + " FROM review_archive WHERE pond_id = ?",
                        (rs, rowNum) -> new ArchiveRow(
                                rs.getLong("id"),
                                rs.getString("request_id"),
                                rs.getLong("pond_id"),
                                (Long) rs.getObject("calc_id"),
                                (Long) rs.getObject("plan_id"),
                                (Long) rs.getObject("command_id"),
                                rs.getString("status"),
                                rs.getObject("archived_at", LocalDateTime.class)),
                        pondId)
                .stream()
                .findFirst();
    }
}
