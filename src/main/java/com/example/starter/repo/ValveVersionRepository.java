package com.example.starter.repo;

import com.example.starter.domain.ValveAction;
import com.example.starter.po.ValveOpeningVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 阀门开度版本链数据访问。
 */
@Repository
public class ValveVersionRepository {

    private final JdbcTemplate jdbc;

    public ValveVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ValveOpeningVersion> MAPPER = (rs, n) -> {
        ValveOpeningVersion v = new ValveOpeningVersion();
        v.setId(rs.getLong("id"));
        v.setValveId(rs.getLong("valve_id"));
        v.setVersionNo(rs.getInt("version_no"));
        v.setFromOpening(rs.getBigDecimal("from_opening"));
        v.setToOpening(rs.getBigDecimal("to_opening"));
        v.setDelta(rs.getBigDecimal("delta"));
        v.setAction(ValveAction.valueOf(rs.getString("action")));
        v.setTaskId(rs.getLong("task_id"));
        v.setRequestId(rs.getString("request_id"));
        v.setChangedAt(rs.getTimestamp("changed_at").toInstant());
        return v;
    };

    /**
     * 追加版本（version_no 由数据库内当前最大值 +1 得到，调用方须持阀门行锁）。
     */
    public void append(long valveId, int versionNo, java.math.BigDecimal from, java.math.BigDecimal to,
                       java.math.BigDecimal delta, ValveAction action, long taskId,
                       String requestId, Instant now) {
        jdbc.update("INSERT INTO valve_opening_version "
                        + "(valve_id, version_no, from_opening, to_opening, delta, action, task_id, request_id, changed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                valveId, versionNo, from, to, delta, action.name(), taskId, requestId, Timestamp.from(now));
    }

    public int maxVersionNo(long valveId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM valve_opening_version WHERE valve_id = ?",
                Integer.class, valveId);
        return max == null ? 0 : max;
    }

    public List<ValveOpeningVersion> findByValve(long valveId) {
        return jdbc.query("SELECT id, valve_id, version_no, from_opening, to_opening, delta, action, task_id, request_id, changed_at "
                + "FROM valve_opening_version WHERE valve_id = ? ORDER BY version_no", MAPPER, valveId);
    }

    public List<ValveOpeningVersion> findByTask(long taskId) {
        return jdbc.query("SELECT id, valve_id, version_no, from_opening, to_opening, delta, action, task_id, request_id, changed_at "
                + "FROM valve_opening_version WHERE task_id = ? ORDER BY id", MAPPER, taskId);
    }

    /** 任务内最近一次开度变更时刻；无任何步进时返回 null。 */
    public Instant findLatestChangeAt(long taskId) {
        List<Timestamp> list = jdbc.query(
                "SELECT MAX(changed_at) FROM valve_opening_version WHERE task_id = ?",
                (rs, n) -> rs.getTimestamp(1), taskId);
        Timestamp ts = list.isEmpty() ? null : list.get(0);
        return ts == null ? null : ts.toInstant();
    }
}
