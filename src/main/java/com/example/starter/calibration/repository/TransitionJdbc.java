package com.example.starter.calibration.repository;

import com.example.starter.calibration.domain.CalibrationStatus;
import com.example.starter.calibration.domain.StatusTransition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 状态流转审计仓储（只追加）。
 */
@Repository
public class TransitionJdbc {

    private final JdbcTemplate jdbc;

    public TransitionJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(StatusTransition transition) {
        jdbc.update("""
                INSERT INTO status_transition (version_id, from_status, to_status, operator, operated_at, remark)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                transition.getVersionId(),
                transition.getFromStatus() == null ? null : transition.getFromStatus().name(),
                transition.getToStatus().name(),
                transition.getOperator(),
                Timestamp.from(transition.getOperatedAt()),
                transition.getRemark());
    }

    public List<StatusTransition> findByVersionId(long versionId) {
        return jdbc.query(
                "SELECT id, version_id, from_status, to_status, operator, operated_at, remark FROM status_transition WHERE version_id = ? ORDER BY id",
                rowMapper(), versionId);
    }

    static RowMapper<StatusTransition> rowMapper() {
        return (rs, n) -> {
            StatusTransition t = new StatusTransition();
            t.setId(rs.getLong("id"));
            t.setVersionId(rs.getLong("version_id"));
            String from = rs.getString("from_status");
            t.setFromStatus(from == null ? null : CalibrationStatus.valueOf(from));
            t.setToStatus(CalibrationStatus.valueOf(rs.getString("to_status")));
            t.setOperator(rs.getString("operator"));
            t.setOperatedAt(rs.getTimestamp("operated_at").toInstant());
            t.setRemark(rs.getString("remark"));
            return t;
        };
    }
}
