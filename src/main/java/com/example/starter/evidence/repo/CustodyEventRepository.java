package com.example.starter.evidence.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.evidence.domain.CustodyEventType;

/**
 * 保管事件表数据访问，仅追加与查询，不提供更新。
 */
@Repository
public class CustodyEventRepository {

    private final JdbcTemplate jdbc;

    public CustodyEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条不可变保管事件。
     */
    public void append(String evidenceId, CustodyEventType type, String detail, Instant occurredAt) {
        jdbc.update(
                "INSERT INTO custody_event (evidence_id, event_type, detail, occurred_at) VALUES (?, ?, ?, ?)",
                evidenceId, type.name(), detail, Timestamp.from(occurredAt));
    }

    /**
     * 按证据查询全部保管事件，按发生顺序返回。
     */
    public List<CustodyEventRow> findByEvidenceId(String evidenceId) {
        return jdbc.query(
                "SELECT * FROM custody_event WHERE evidence_id = ? ORDER BY id",
                this::mapRow, evidenceId);
    }

    private CustodyEventRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CustodyEventRow(
                rs.getLong("id"),
                rs.getString("evidence_id"),
                CustodyEventType.valueOf(rs.getString("event_type")),
                rs.getString("detail"),
                rs.getTimestamp("occurred_at").toInstant());
    }
}
