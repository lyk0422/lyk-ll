package com.example.starter.repo;

import com.example.starter.domain.ValveAction;
import com.example.starter.po.AdjustmentRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 单元调节台账数据访问：支撑 24 小时(UTC)调节次数统计与复算。
 */
@Repository
public class AdjustmentRecordRepository {

    private final JdbcTemplate jdbc;

    public AdjustmentRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AdjustmentRecord> MAPPER = (rs, n) -> {
        AdjustmentRecord r = new AdjustmentRecord();
        r.setId(rs.getLong("id"));
        r.setTaskId(rs.getLong("task_id"));
        r.setUnitId(rs.getLong("unit_id"));
        r.setValveId(rs.getLong("valve_id"));
        r.setRequestId(rs.getString("request_id"));
        r.setStepIndex(rs.getInt("step_index"));
        r.setAction(ValveAction.valueOf(rs.getString("action")));
        r.setFromOpening(rs.getBigDecimal("from_opening"));
        r.setToOpening(rs.getBigDecimal("to_opening"));
        r.setDelta(rs.getBigDecimal("delta"));
        r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return r;
    };

    public void insert(long taskId, long unitId, long valveId, String requestId, int stepIndex,
                       ValveAction action, BigDecimal from, BigDecimal to, BigDecimal delta, Instant now) {
        jdbc.update("INSERT INTO adjustment_record "
                        + "(task_id, unit_id, valve_id, request_id, step_index, action, from_opening, to_opening, delta, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, unitId, valveId, requestId, stepIndex, action.name(),
                from, to, delta, Timestamp.from(now));
    }

    /**
     * 统计单元在半开区间 (windowStart, now]（UTC）内已发生的调节次数。
     * 恰满 24 小时整的旧记录已滑出窗口（created_at 严格晚于 windowStart）；
     * 窗口内恰有 3 次记录时为合法上限。
     */
    public int countByUnitSince(long unitId, Instant windowStart) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM adjustment_record WHERE unit_id = ? AND created_at > ?",
                Integer.class, unitId, Timestamp.from(windowStart));
        return count == null ? 0 : count;
    }

    public List<AdjustmentRecord> findByTask(long taskId) {
        return jdbc.query("SELECT id, task_id, unit_id, valve_id, request_id, step_index, action, from_opening, to_opening, delta, created_at "
                + "FROM adjustment_record WHERE task_id = ? ORDER BY id", MAPPER, taskId);
    }
}
