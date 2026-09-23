package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.EntityType;
import com.example.starter.domain.EventKind;

/**
 * 保管链事件仓储。
 */
@Repository
public class CustodyEventRepository {

    private static final String COLUMNS =
            "id, entity_type, entity_id, event_kind, from_custodian, to_custodian, "
                    + "version_before, version_after, occurred_at, note";

    private static final RowMapper<CustodyEventRow> MAPPER = CustodyEventRepository::mapRow;

    private final JdbcTemplate jdbc;

    public CustodyEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加保管链事件。
     */
    public void append(EntityType entityType, String entityId, EventKind eventKind,
                       String fromCustodian, String toCustodian,
                       int versionBefore, int versionAfter, Instant occurredAt, String note) {
        jdbc.update(
                "INSERT INTO custody_event (entity_type, entity_id, event_kind, from_custodian, to_custodian, "
                        + "version_before, version_after, occurred_at, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                entityType.name(), entityId, eventKind.name(), fromCustodian, toCustodian,
                versionBefore, versionAfter, Timestamp.from(occurredAt), note);
    }

    /**
     * 查询实体的全部保管链事件（按序号升序，同序按时间升序）。
     */
    public List<CustodyEventRow> findByEntity(EntityType entityType, String entityId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM custody_event "
                        + "WHERE entity_type = ? AND entity_id = ? ORDER BY id",
                MAPPER, entityType.name(), entityId);
    }

    /**
     * 查询实体最近一条保管链事件。
     */
    public Optional<CustodyEventRow> findLastByEntity(EntityType entityType, String entityId) {
        List<CustodyEventRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM custody_event "
                        + "WHERE entity_type = ? AND entity_id = ? ORDER BY id DESC LIMIT 1",
                MAPPER, entityType.name(), entityId);
        return rows.stream().findFirst();
    }

    /**
     * 判断完全相同的交接事件（类型、实体、源、目标保管人均相同）是否已存在。
     */
    public boolean existsSameTransfer(EntityType entityType, String entityId,
                                      String fromCustodian, String toCustodian) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM custody_event WHERE entity_type = ? AND entity_id = ? "
                        + "AND event_kind = ? AND from_custodian = ? AND to_custodian = ?",
                Integer.class, entityType.name(), entityId, EventKind.TRANSFER.name(),
                fromCustodian, toCustodian);
        return count != null && count > 0;
    }

    private static CustodyEventRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        String from = rs.getString("from_custodian");
        boolean fromNull = rs.wasNull();
        String to = rs.getString("to_custodian");
        int versionBefore = rs.getInt("version_before");
        int versionAfter = rs.getInt("version_after");
        Instant occurredAt = rs.getTimestamp("occurred_at").toInstant();
        String note = rs.getString("note");
        boolean noteNull = rs.wasNull();
        return new CustodyEventRow(
                rs.getLong("id"),
                EntityType.valueOf(rs.getString("entity_type")),
                rs.getString("entity_id"),
                EventKind.valueOf(rs.getString("event_kind")),
                fromNull ? null : from,
                to,
                versionBefore,
                versionAfter,
                occurredAt,
                noteNull ? null : note);
    }
}
