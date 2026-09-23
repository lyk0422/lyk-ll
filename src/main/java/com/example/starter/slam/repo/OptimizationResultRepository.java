package com.example.starter.slam.repo;

import com.example.starter.slam.domain.OptimizationResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 优化结果表数据访问；每个图版本至多一条结果，旧版本结果不得覆盖更新版本结果。
 */
@Repository
public class OptimizationResultRepository {

    public static final RowMapper<OptimizationResult> ROW_MAPPER = (rs, n) -> new OptimizationResult(
            rs.getLong("id"),
            rs.getLong("task_id"),
            rs.getInt("graph_version"),
            rs.getString("poses_json"),
            rs.getDouble("final_cost"),
            rs.getInt("iterations"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public OptimizationResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入结果并回填自增 ID。opt_results.graph_version 上有唯一约束，
     * 配合版本检查防止同一版本重复写入。
     */
    public long insert(OptimizationResult result) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO opt_results
                                (task_id, graph_version, poses_json, final_cost, iterations, created_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, result.taskId());
            ps.setInt(2, result.graphVersion());
            ps.setString(3, result.posesJson());
            ps.setDouble(4, result.finalCost());
            ps.setInt(5, result.iterations() == null ? 0 : result.iterations());
            ps.setTimestamp(6, Timestamp.from(result.createdAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to obtain opt_results generated key");
        }
        return key.longValue();
    }

    public OptimizationResult findByTaskId(long taskId) {
        List<OptimizationResult> rows = jdbc.query(
                "SELECT * FROM opt_results WHERE task_id = ?", ROW_MAPPER, taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 当前最新图版本的结果；不存在返回 null。
     */
    public OptimizationResult findLatest() {
        List<OptimizationResult> rows = jdbc.query(
                "SELECT * FROM opt_results ORDER BY graph_version DESC LIMIT 1", ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 已落库结果中的最高图版本；无结果时返回 -1。
     */
    public int latestVersion() {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(graph_version), -1) FROM opt_results", Integer.class);
        return v == null ? -1 : v;
    }
}
