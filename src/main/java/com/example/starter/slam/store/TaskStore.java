package com.example.starter.slam.store;

import com.example.starter.slam.domain.TaskStatus;
import com.example.starter.slam.po.OptimizeResultPO;
import com.example.starter.slam.po.OptimizeTaskPO;
import com.example.starter.slam.po.TaskRequestPO;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 优化任务、客户端请求关系与优化结果数据访问。
 */
@Repository
public class TaskStore {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public TaskStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private static final String TASK_COLUMNS =
            "task_id, graph_id, target_version, status, attempts, failure_reason, snapshot_json, "
                    + "snapshot_summary, result_version, created_at, updated_at, started_at, finished_at";

    public OptimizeTaskPO findTask(String taskId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + TASK_COLUMNS + " FROM optimize_task WHERE task_id = ?", TASK_MAPPER, taskId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public TaskRequestPO findRequest(String requestId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT request_id, task_id, graph_id, target_version, merged_into_task_id, created_at "
                            + "FROM task_request WHERE request_id = ?", REQUEST_MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public void insertTask(OptimizeTaskPO task) {
        jdbcTemplate.update(
                "INSERT INTO optimize_task (task_id, graph_id, target_version, status, attempts, failure_reason, "
                        + "snapshot_json, snapshot_summary, result_version, created_at, updated_at, "
                        + "started_at, finished_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                task.taskId(), task.graphId(), task.targetVersion(), task.status().name(), task.attempts(),
                task.failureReason(), task.snapshotJson(), task.snapshotSummary(), task.resultVersion(),
                Timestamp.from(task.createdAt()), Timestamp.from(task.updatedAt()),
                task.startedAt() == null ? null : Timestamp.from(task.startedAt()),
                task.finishedAt() == null ? null : Timestamp.from(task.finishedAt()));
    }

    public void insertRequest(TaskRequestPO request) {
        jdbcTemplate.update(
                "INSERT INTO task_request (request_id, task_id, graph_id, target_version, "
                        + "merged_into_task_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                request.requestId(), request.taskId(), request.graphId(), request.targetVersion(),
                request.mergedIntoTaskId(), Timestamp.from(request.createdAt()));
    }

    /**
     * 查找同一图上可被合并的、尚未开始（PENDING）的最老任务；不存在返回 null。
     */
    public OptimizeTaskPO findMergablePendingTask(String graphId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + TASK_COLUMNS + " FROM optimize_task "
                            + "WHERE graph_id = ? AND status = 'PENDING' ORDER BY created_at, task_id LIMIT 1",
                    TASK_MAPPER, graphId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 将请求合并到承接任务：请求记录指向承接任务，任务目标版本提升为最高版本。
     */
    public void mergeInto(String requestId, String survivingTaskId, long newTargetVersion, Instant now) {
        jdbcTemplate.update(
                "UPDATE optimize_task SET target_version = ?, updated_at = ? "
                        + "WHERE task_id = ? AND status = 'PENDING'",
                newTargetVersion, Timestamp.from(now), survivingTaskId);
    }

    /**
     * 查询任务关联的全部请求 ID（自身请求 + 被合并请求），按请求创建顺序。
     */
    public List<String> requestIdsForTask(String taskId) {
        return jdbcTemplate.queryForList(
                "SELECT request_id FROM task_request WHERE task_id = ? OR merged_into_task_id = ? "
                        + "ORDER BY created_at, request_id",
                String.class, taskId, taskId);
    }

    /**
     * 原子领取最老的 PENDING 任务：置为 RUNNING、attempts+1、记录开始时间。无任务返回 null。
     */
    public OptimizeTaskPO claimNext(Instant now) {
        return transactionTemplate.execute(status -> {
            OptimizeTaskPO next;
            try {
                next = jdbcTemplate.queryForObject(
                        "SELECT " + TASK_COLUMNS + " FROM optimize_task "
                                + "WHERE status = 'PENDING' ORDER BY created_at, task_id LIMIT 1 FOR UPDATE",
                        TASK_MAPPER);
            } catch (EmptyResultDataAccessException ex) {
                return null;
            }
            jdbcTemplate.update(
                    "UPDATE optimize_task SET status = 'RUNNING', attempts = attempts + 1, "
                            + "started_at = ?, finished_at = NULL, failure_reason = NULL, updated_at = ? "
                            + "WHERE task_id = ?",
                    Timestamp.from(now), Timestamp.from(now), next.taskId());
            return findTask(next.taskId());
        });
    }

    /**
     * 任务执行前固化输入快照（仅首次执行写入；重试复用既有快照）。
     */
    public void attachSnapshot(String taskId, String snapshotJson, String snapshotSummary, Instant now) {
        jdbcTemplate.update(
                "UPDATE optimize_task SET snapshot_json = ?, snapshot_summary = ?, updated_at = ? "
                        + "WHERE task_id = ? AND snapshot_json IS NULL",
                snapshotJson, snapshotSummary, Timestamp.from(now), taskId);
    }

    public void markSucceeded(String taskId, Long resultVersion, int attempts, Instant now) {
        jdbcTemplate.update(
                "UPDATE optimize_task SET status = 'SUCCEEDED', result_version = ?, attempts = ?, "
                        + "finished_at = ?, updated_at = ? WHERE task_id = ?",
                resultVersion, attempts, Timestamp.from(now), Timestamp.from(now), taskId);
    }

    public void markFailedRetryable(String taskId, int attempts, String reason, Instant now) {
        jdbcTemplate.update(
                "UPDATE optimize_task SET status = 'FAILED_RETRYABLE', attempts = ?, failure_reason = ?, "
                        + "finished_at = ?, updated_at = ? WHERE task_id = ?",
                attempts, reason, Timestamp.from(now), Timestamp.from(now), taskId);
    }

    public void markSuperseded(String taskId, int attempts, Instant now) {
        jdbcTemplate.update(
                "UPDATE optimize_task SET status = 'SUPERSEDED', attempts = ?, finished_at = ?, "
                        + "updated_at = ? WHERE task_id = ?",
                attempts, Timestamp.from(now), Timestamp.from(now), taskId);
    }

    /**
     * 把可重试失败任务重新置为 PENDING（复用原快照，不重建）。
     */
    public void requeueForRetry(String taskId, Instant now) {
        int updated = jdbcTemplate.update(
                "UPDATE optimize_task SET status = 'PENDING', updated_at = ? "
                        + "WHERE task_id = ? AND status = 'FAILED_RETRYABLE'",
                Timestamp.from(now), taskId);
        if (updated == 0) {
            throw new IllegalStateException("task is not in FAILED_RETRYABLE state: " + taskId);
        }
    }

    public void insertResult(OptimizeResultPO result) {
        jdbcTemplate.update(
                "INSERT INTO optimize_result (result_id, task_id, graph_id, graph_version, frame_ids_json, "
                        + "poses_json, cost, iterations, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                result.resultId(), result.taskId(), result.graphId(), result.graphVersion(),
                result.frameIdsJson(), result.posesJson(), result.cost(), result.iterations(),
                Timestamp.from(result.createdAt()));
    }

    public OptimizeResultPO findResultByTask(String taskId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT result_id, task_id, graph_id, graph_version, frame_ids_json, poses_json, cost, "
                            + "iterations, created_at FROM optimize_result WHERE task_id = ?",
                    RESULT_MAPPER, taskId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 查询图上最新成功结果（图版本最高，同版本取最近生成）。
     */
    public OptimizeResultPO findLatestSucceededResult(String graphId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT result_id, task_id, graph_id, graph_version, frame_ids_json, poses_json, cost, "
                            + "iterations, created_at FROM optimize_result WHERE graph_id = ? "
                            + "ORDER BY graph_version DESC, created_at DESC LIMIT 1",
                    RESULT_MAPPER, graphId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 在事务内锁定任务行并回读，用于完成阶段的状态保护。
     */
    public OptimizeTaskPO lockTask(String taskId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + TASK_COLUMNS + " FROM optimize_task WHERE task_id = ? FOR UPDATE",
                    TASK_MAPPER, taskId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private static final RowMapper<OptimizeTaskPO> TASK_MAPPER = (rs, rowNum) -> new OptimizeTaskPO(
            rs.getString("task_id"),
            rs.getString("graph_id"),
            rs.getLong("target_version"),
            TaskStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            rs.getString("failure_reason"),
            rs.getString("snapshot_json"),
            rs.getString("snapshot_summary"),
            (Long) rs.getObject("result_version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());

    private static final RowMapper<TaskRequestPO> REQUEST_MAPPER = (rs, rowNum) -> new TaskRequestPO(
            rs.getString("request_id"),
            rs.getString("task_id"),
            rs.getString("graph_id"),
            rs.getLong("target_version"),
            rs.getString("merged_into_task_id"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<OptimizeResultPO> RESULT_MAPPER = (rs, rowNum) -> new OptimizeResultPO(
            rs.getString("result_id"),
            rs.getString("task_id"),
            rs.getString("graph_id"),
            rs.getLong("graph_version"),
            rs.getString("frame_ids_json"),
            rs.getString("poses_json"),
            rs.getDouble("cost"),
            rs.getInt("iterations"),
            rs.getTimestamp("created_at").toInstant());
}
