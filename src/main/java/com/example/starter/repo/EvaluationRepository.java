package com.example.starter.repo;

import com.example.starter.domain.EvaluationPhase;
import com.example.starter.po.EvaluationRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 评估记录数据访问。
 */
@Repository
public class EvaluationRepository {

    private final JdbcTemplate jdbc;

    public EvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<EvaluationRecord> MAPPER = (rs, n) -> {
        EvaluationRecord e = new EvaluationRecord();
        e.setId(rs.getLong("id"));
        e.setTaskId(rs.getLong("task_id"));
        e.setStationId(rs.getLong("station_id"));
        e.setRequestId(rs.getString("request_id"));
        e.setPhase(EvaluationPhase.valueOf(rs.getString("phase")));
        e.setMaxReturnTemp(rs.getBigDecimal("max_return_temp"));
        e.setMinReturnTemp(rs.getBigDecimal("min_return_temp"));
        e.setTempDiff(rs.getBigDecimal("temp_diff"));
        e.setBalanced(rs.getBoolean("balanced"));
        e.setDetailJson(rs.getString("detail_json"));
        e.setEvaluatedAt(rs.getTimestamp("evaluated_at").toInstant());
        return e;
    };

    private static final String COLUMNS =
            "SELECT id, task_id, station_id, request_id, phase, max_return_temp, min_return_temp, "
                    + "temp_diff, balanced, detail_json, evaluated_at FROM evaluation_record ";

    public void insert(long taskId, long stationId, String requestId, EvaluationPhase phase,
                       BigDecimal max, BigDecimal min, BigDecimal diff, boolean balanced,
                       String detailJson, Instant now) {
        jdbc.update("INSERT INTO evaluation_record "
                        + "(task_id, station_id, request_id, phase, max_return_temp, min_return_temp, temp_diff, balanced, detail_json, evaluated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, stationId, requestId, phase.name(), max, min, diff, balanced,
                detailJson, Timestamp.from(now));
    }

    public EvaluationRecord findLatestByTaskAndPhase(long taskId, EvaluationPhase phase) {
        List<EvaluationRecord> list = jdbc.query(
                COLUMNS + "WHERE task_id = ? AND phase = ? ORDER BY id DESC LIMIT 1",
                MAPPER, taskId, phase.name());
        return list.isEmpty() ? null : list.get(0);
    }

    public List<EvaluationRecord> findByTask(long taskId) {
        return jdbc.query(COLUMNS + "WHERE task_id = ? ORDER BY id", MAPPER, taskId);
    }
}
