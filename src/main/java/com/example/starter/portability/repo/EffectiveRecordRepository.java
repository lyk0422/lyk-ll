package com.example.starter.portability.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.portability.EffectiveRecord;

/**
 * 生效记录表数据访问；flow_id 唯一约束保证同一流程重复广播只产生一条生效记录。
 */
@Repository
public class EffectiveRecordRepository {

    private final JdbcTemplate jdbc;

    public EffectiveRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long flowId, String msisdn, String broadcastRequestId, Instant effectiveAt) {
        jdbc.update("INSERT INTO effective_record (flow_id, msisdn, broadcast_request_id, effective_at)"
                        + " VALUES (?,?,?,?)",
                flowId, msisdn, broadcastRequestId, Timestamp.from(effectiveAt));
    }

    public Optional<EffectiveRecord> findByFlowId(long flowId) {
        return jdbc.query("SELECT * FROM effective_record WHERE flow_id = ?",
                EffectiveRecordRepository::mapRow, flowId).stream().findFirst();
    }

    public long countByFlowId(long flowId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM effective_record WHERE flow_id = ?", Long.class, flowId);
        return count == null ? 0 : count;
    }

    private static EffectiveRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new EffectiveRecord(
                rs.getLong("id"),
                rs.getLong("flow_id"),
                rs.getString("msisdn"),
                rs.getString("broadcast_request_id"),
                rs.getTimestamp("effective_at").toInstant());
    }
}
