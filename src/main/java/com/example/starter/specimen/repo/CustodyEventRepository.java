package com.example.starter.specimen.repo;

import com.example.starter.specimen.model.CustodyEventRow;
import com.example.starter.specimen.model.EntityType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 保管交接事件表数据访问：事件只增不改，污染隔离后历史事件保留。
 */
@Repository
public class CustodyEventRepository {

    private static final RowMapper<CustodyEventRow> MAPPER = (rs, n) -> new CustodyEventRow(
            rs.getLong("id"),
            EntityType.valueOf(rs.getString("entity_type")),
            rs.getString("entity_id"),
            rs.getString("from_custodian"),
            rs.getString("to_custodian"),
            rs.getTimestamp("event_at").toInstant(),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public CustodyEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CustodyEventRow row) {
        jdbc.update("INSERT INTO custody_event (entity_type, entity_id, from_custodian, to_custodian,"
                        + " event_at, request_id, created_at) VALUES (?,?,?,?,?,?,?)",
                row.entityType().name(), row.entityId(), row.fromCustodian(), row.toCustodian(),
                Timestamp.from(row.eventAt()), row.requestId(), Timestamp.from(row.createdAt()));
    }

    public List<CustodyEventRow> findByEntity(EntityType entityType, String entityId) {
        return jdbc.query("SELECT * FROM custody_event WHERE entity_type = ? AND entity_id = ?"
                        + " ORDER BY id", MAPPER, entityType.name(), entityId);
    }
}
