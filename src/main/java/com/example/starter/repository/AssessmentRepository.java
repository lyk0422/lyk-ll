package com.example.starter.repository;

import com.example.starter.domain.Assessment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 调节效果评估数据访问。
 */
@Repository
public class AssessmentRepository {

    private static final RowMapper<Assessment> MAPPER = (rs, n) -> new Assessment(
            rs.getLong("id"), rs.getLong("task_id"), rs.getString("request_id"),
            rs.getString("fingerprint"), rs.getBigDecimal("before_diff"),
            rs.getBigDecimal("after_diff"), rs.getBoolean("balanced"),
            rs.getString("detail"), rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS = "id, task_id, request_id, fingerprint,"
            + " before_diff, after_diff, balanced, detail, created_at";

    private final JdbcTemplate jdbc;

    public AssessmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入评估记录。
     */
    public void insert(Assessment assessment) {
        jdbc.update("INSERT INTO assessment (task_id, request_id, fingerprint,"
                        + " before_diff, after_diff, balanced, detail, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                assessment.taskId(), assessment.requestId(), assessment.fingerprint(),
                assessment.beforeDiff(), assessment.afterDiff(), assessment.balanced(),
                assessment.detail(), Timestamp.from(assessment.createdAt()));
    }

    /**
     * 按幂等键查询评估。
     */
    public Optional<Assessment> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM assessment WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 按任务 ID 查询评估。
     */
    public Optional<Assessment> findByTaskId(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM assessment WHERE task_id = ?",
                MAPPER, taskId).stream().findFirst();
    }
}
