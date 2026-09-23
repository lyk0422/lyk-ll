package com.example.starter.slam.repo;

import com.example.starter.slam.domain.OptimizationTask;
import com.example.starter.slam.model.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

/**
 * 优化任务表数据访问。
 */
@Repository
public class OptimizationTaskRepository {

    public static final RowMapper<OptimizationTask> ROW_MAPPER = (rs, n) -> new OptimizationTask(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getInt("target_graph_version"),
            TaskStatus.valueOf(rs.getString("status")),
            rs.getString("snapshot_json"),
            rs.getString("snapshot_digest"),
            (Long) rs.getObject("merged_into_id"),
            rs.getInt("retry_count"),
            rs.getString("failure_reason"),
            rs.getTimestamp("created_at").toInstant(),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("finished_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private final JdbcTemplate jdbc;

    public OptimizationTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OptimizationTask findById(long id) {
        List<OptimizationTask> rows = jdbc.query(
                "SELECT * FROM opt_tasks WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public OptimizationTask findByRequestId(String requestId) {
        List<OptimizationTask> rows = jdbc.query(
                "SELECT * FROM opt_tasks WHERE request_id = ?", ROW_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * requestId 是否已被优化任务占用（图写入与优化提交共用幂等键空间）。
     */
    public boolean existsByRequestId(String requestId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM opt_tasks WHERE request_id = ?", Integer.class, requestId);
        return count != null && count > 0;
    }

    /**
     * 插入任务并回填自增 ID。
     */
    public long insert(OptimizationTask task) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO opt_tasks
                                (request_id, target_graph_version, status, snapshot_json,
                                 snapshot_digest, merged_into_id, retry_count, failure_reason,
                                 created_at, started_at, finished_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, task.requestId());
            ps.setInt(2, task.targetGraphVersion());
            ps.setString(3, task.status().name());
            ps.setString(4, task.snapshotJson());
            ps.setString(5, task.snapshotDigest());
            if (task.mergedIntoId() == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, task.mergedIntoId());
            }
            ps.setInt(7, task.retryCount());
            ps.setString(8, task.failureReason());
            ps.setTimestamp(9, Timestamp.from(task.createdAt()));
            ps.setTimestamp(10, task.startedAt() == null ? null : Timestamp.from(task.startedAt()));
            ps.setTimestamp(11, task.finishedAt() == null ? null : Timestamp.from(task.finishedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to obtain opt_tasks generated key");
        }
        return key.longValue();
    }

    /**
     * 查找同一图上尚未开始（PENDING）、未被合并的任务，按 ID 升序（最早提交在前）。
     */
    public List<OptimizationTask> findPendingForMerge() {
        return jdbc.query("""
                        SELECT * FROM opt_tasks
                        WHERE status = 'PENDING' AND merged_into_id IS NULL
                        ORDER BY id
                        """, ROW_MAPPER);
    }

    /**
     * 将被合并任务置为 MERGED 并指向最终执行任务。
     */
    public void markMerged(long taskId, long mergedIntoId) {
        jdbc.update("UPDATE opt_tasks SET status = 'MERGED', merged_into_id = ? WHERE id = ?",
                mergedIntoId, taskId);
    }

    /**
     * 重试前将 RETRYABLE_FAILED 任务重置回 PENDING（快照字段保持不变）。
     * 返回是否重置成功。
     */
    public boolean resetForRetry(long taskId) {
        int updated = jdbc.update("""
                        UPDATE opt_tasks
                        SET status = 'PENDING', started_at = NULL, finished_at = NULL
                        WHERE id = ? AND status = 'RETRYABLE_FAILED' AND merged_into_id IS NULL
                        """,
                taskId);
        return updated == 1;
    }

    /**
     * 合并后更新最终执行任务的目标版本与快照为最高图版本。
     */
    public void updateTargetVersion(long taskId, int targetGraphVersion, String snapshotJson, String snapshotDigest) {
        jdbc.update("""
                        UPDATE opt_tasks
                        SET target_graph_version = ?, snapshot_json = ?, snapshot_digest = ?
                        WHERE id = ?
                        """,
                targetGraphVersion, snapshotJson, snapshotDigest, taskId);
    }

    /**
     * 乐观抢占：仅当任务仍为 PENDING 且未被合并时置为 RUNNING。返回是否抢占成功。
     */
    public boolean markRunning(long taskId, Instant startedAt) {
        int updated = jdbc.update("""
                        UPDATE opt_tasks
                        SET status = 'RUNNING', started_at = ?
                        WHERE id = ? AND status = 'PENDING' AND merged_into_id IS NULL
                        """,
                Timestamp.from(startedAt), taskId);
        return updated == 1;
    }

    public void markSucceeded(long taskId, Instant finishedAt) {
        jdbc.update("UPDATE opt_tasks SET status = 'SUCCEEDED', finished_at = ? WHERE id = ?",
                Timestamp.from(finishedAt), taskId);
    }

    public void markFailed(long taskId, String failureReason, int retryCount, Instant finishedAt) {
        jdbc.update("""
                        UPDATE opt_tasks
                        SET status = 'RETRYABLE_FAILED', failure_reason = ?, retry_count = ?,
                            finished_at = ?
                        WHERE id = ?
                        """,
                truncate(failureReason), retryCount, Timestamp.from(finishedAt), taskId);
    }

    /**
     * 重试次数 +1（快照字段不变，保证重试使用原快照）。
     */
    public void incrementRetryCount(long taskId, int retryCount) {
        jdbc.update("UPDATE opt_tasks SET retry_count = ? WHERE id = ?", retryCount, taskId);
    }

    public void markSuperseded(long taskId, Instant finishedAt) {
        jdbc.update("UPDATE opt_tasks SET status = 'SUPERSEDED', finished_at = ? WHERE id = ?",
                Timestamp.from(finishedAt), taskId);
    }

    public List<OptimizationTask> findByStatus(TaskStatus status) {
        return jdbc.query("SELECT * FROM opt_tasks WHERE status = ? ORDER BY id",
                ROW_MAPPER, status.name());
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 2000 ? reason : reason.substring(0, 2000);
    }
}
