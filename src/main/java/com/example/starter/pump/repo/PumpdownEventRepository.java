package com.example.starter.pump.repo;

import com.example.starter.pump.domain.PumpdownEvent;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 预降事件仓储。
 */
@Repository
public class PumpdownEventRepository {

    private static final RowMapper<PumpdownEvent> MAPPER = (rs, n) -> new PumpdownEvent(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("zone_code"),
            rs.getString("alert_level"),
            rs.getString("status"),
            rs.getBigDecimal("target_level"),
            rs.getObject("started_at", LocalDateTime.class),
            rs.getObject("archived_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public PumpdownEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入预降事件并回填自增ID。 */
    public PumpdownEvent create(String requestId, String zoneCode, String alertLevel,
                                BigDecimal targetLevel, LocalDateTime startedAt) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator creator = con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    """
                    INSERT INTO pumpdown_event (request_id, zone_code, alert_level, status,
                        target_level, started_at, archived_at)
                    VALUES (?, ?, ?, 'ACTIVE', ?, ?, NULL)
                    """,
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setString(2, zoneCode);
            ps.setString(3, alertLevel);
            ps.setBigDecimal(4, targetLevel);
            ps.setObject(5, startedAt);
            return ps;
        };
        jdbc.update(creator, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("预降事件自增ID回填失败");
        }
        return new PumpdownEvent(key.longValue(), requestId, zoneCode, alertLevel, "ACTIVE",
                targetLevel, startedAt, null);
    }

    /** 按ID查询事件，不存在返回空。 */
    public Optional<PumpdownEvent> findById(Long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT id, request_id, zone_code, alert_level, status, target_level,
                        started_at, archived_at
                    FROM pumpdown_event WHERE id = ?
                    """,
                    MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 按 requestId 查询事件（用于预警同键重放）。 */
    public Optional<PumpdownEvent> findByRequestId(String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT id, request_id, zone_code, alert_level, status, target_level,
                        started_at, archived_at
                    FROM pumpdown_event WHERE request_id = ?
                    """,
                    MAPPER, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查询某分区当前是否存在活动（未归档）预降事件。 */
    public Optional<PumpdownEvent> findActiveByZone(String zoneCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT id, request_id, zone_code, alert_level, status, target_level,
                        started_at, archived_at
                    FROM pumpdown_event WHERE zone_code = ? AND status = 'ACTIVE'
                    ORDER BY id DESC LIMIT 1
                    """,
                    MAPPER, zoneCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查询某分区最近一条预降事件（含已归档），用于迟到数据归属判断。 */
    public Optional<PumpdownEvent> findLatestByZone(String zoneCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT id, request_id, zone_code, alert_level, status, target_level,
                        started_at, archived_at
                    FROM pumpdown_event WHERE zone_code = ? ORDER BY id DESC LIMIT 1
                    """,
                    MAPPER, zoneCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 转为归档终态。 */
    public void markArchived(Long pumpdownId, LocalDateTime archivedAt) {
        jdbc.update(
                "UPDATE pumpdown_event SET status = 'ARCHIVED', archived_at = ? WHERE id = ?",
                archivedAt, pumpdownId);
    }
}
