package com.example.starter.audit.persist;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 审计平台 JdbcTemplate 仓储。
 *
 * <p>每个写方法独立事务提交：阶段按依赖顺序逐步执行，失败或中断时之前成功阶段的检查点
 * 必须保留，因此编排层不使用包裹全部阶段的大事务。</p>
 */
@Repository
public class AuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------- 任务 ----------------

    @Transactional
    public long insertTask(TaskPO po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO audit_task (task_code, request_id, status, code_version, config_version, "
                            + "map_version, rosbag_version, seed, fail_stage, fail_times, interrupt_after, "
                            + "error_message, started_at, finished_at, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.getTaskCode());
            ps.setString(2, po.getRequestId());
            ps.setString(3, po.getStatus());
            ps.setString(4, po.getCodeVersion());
            ps.setString(5, po.getConfigVersion());
            ps.setString(6, po.getMapVersion());
            ps.setString(7, po.getRosbagVersion());
            ps.setLong(8, po.getSeed());
            setNullableString(ps, 9, po.getFailStage());
            ps.setInt(10, po.getFailTimes() == null ? 0 : po.getFailTimes());
            setNullableString(ps, 11, po.getInterruptAfter());
            setNullableString(ps, 12, po.getErrorMessage());
            setNullableLong(ps, 13, po.getStartedAt());
            setNullableLong(ps, 14, po.getFinishedAt());
            ps.setLong(15, po.getCreatedAt());
            ps.setLong(16, po.getUpdatedAt());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Transactional
    public void updateTaskExecution(long taskId, String status, String resultHash,
                                    String errorMessage, Long finishedAt, long updatedAt) {
        jdbcTemplate.update(
                "UPDATE audit_task SET status = ?, result_hash = ?, error_message = ?, "
                        + "finished_at = ?, updated_at = ? WHERE id = ?",
                status, resultHash, errorMessage, finishedAt, updatedAt, taskId);
    }

    /** 开始（或恢复）执行：任务置运行中，清空终态字段，不覆盖 started_at。 */
    @Transactional
    public void markTaskRunning(long taskId, long updatedAt) {
        jdbcTemplate.update(
                "UPDATE audit_task SET status = 'RUNNING', result_hash = NULL, error_message = NULL, "
                        + "finished_at = NULL, updated_at = ? WHERE id = ?",
                updatedAt, taskId);
    }

    public TaskPO findTaskById(long taskId) {
        try {
            return jdbcTemplate.queryForObject("SELECT * FROM audit_task WHERE id = ?",
                    TASK_MAPPER, taskId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 阶段 ----------------

    @Transactional
    public void insertStage(StagePO po) {
        jdbcTemplate.update(
                "INSERT INTO task_stage (task_id, stage, seq_no, status, attempt, started_at, finished_at, "
                        + "metrics_json, error_message, artifact_path, artifact_sha256, replay_command) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                po.getTaskId(), po.getStage(), po.getSeqNo(), po.getStatus(), po.getAttempt(),
                po.getStartedAt(), po.getFinishedAt(), po.getMetricsJson(), po.getErrorMessage(),
                po.getArtifactPath(), po.getArtifactSha256(), po.getReplayCommand());
    }

    @Transactional
    public void markStageRunning(long taskId, String stage, int attempt, long startedAt) {
        jdbcTemplate.update(
                "UPDATE task_stage SET status = 'RUNNING', attempt = ?, started_at = ?, "
                        + "finished_at = NULL, error_message = NULL WHERE task_id = ? AND stage = ?",
                attempt, startedAt, taskId, stage);
    }

    /** 阶段成功：落指标、产物与重放命令，并同时写成功检查点（同一事务）。 */
    @Transactional
    public void completeStageWithCheckpoint(StagePO stage, CheckpointPO checkpoint) {
        jdbcTemplate.update(
                "UPDATE task_stage SET status = 'SUCCESS', finished_at = ?, metrics_json = ?, "
                        + "error_message = NULL, artifact_path = ?, artifact_sha256 = ?, replay_command = ? "
                        + "WHERE task_id = ? AND stage = ?",
                stage.getFinishedAt(), stage.getMetricsJson(), stage.getArtifactPath(),
                stage.getArtifactSha256(), stage.getReplayCommand(),
                stage.getTaskId(), stage.getStage());
        insertCheckpoint(checkpoint);
    }

    /** 阶段失败：记录失败状态与原因（独立提交，保留之前检查点）。 */
    @Transactional
    public void markStageFailed(long taskId, String stage, long finishedAt, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE task_stage SET status = 'FAILED', finished_at = ?, error_message = ? "
                        + "WHERE task_id = ? AND stage = ?",
                finishedAt, errorMessage, taskId, stage);
    }

    /** 中断注入：已成功阶段保持 SUCCESS，后续待执行阶段保持 PENDING，任务置 INTERRUPTED。 */
    @Transactional
    public void markRemainingStagesInterrupted(long taskId, String fromStageExclusive) {
        jdbcTemplate.update(
                "UPDATE task_stage SET status = 'INTERRUPTED' WHERE task_id = ? AND status = 'PENDING' "
                        + "AND seq_no > (SELECT seq_no FROM (SELECT seq_no FROM task_stage "
                        + "WHERE task_id = ? AND stage = ?) t)",
                taskId, taskId, fromStageExclusive);
    }

    public List<StagePO> findStagesByTaskId(long taskId) {
        return jdbcTemplate.query(
                "SELECT * FROM task_stage WHERE task_id = ? ORDER BY seq_no",
                STAGE_MAPPER, taskId);
    }

    public StagePO findStage(long taskId, String stage) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM task_stage WHERE task_id = ? AND stage = ?",
                    STAGE_MAPPER, taskId, stage);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 检查点 ----------------

    private void insertCheckpoint(CheckpointPO po) {
        jdbcTemplate.update(
                "INSERT INTO stage_checkpoint (task_id, stage, checkpoint_seq, code_version, config_version, "
                        + "map_version, rosbag_version, seed, upstream_digests_json, stage_output_digest, "
                        + "artifact_path, artifact_sha256, metrics_json, replay_command, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                po.getTaskId(), po.getStage(), po.getCheckpointSeq(), po.getCodeVersion(),
                po.getConfigVersion(), po.getMapVersion(), po.getRosbagVersion(), po.getSeed(),
                po.getUpstreamDigestsJson(), po.getStageOutputDigest(), po.getArtifactPath(),
                po.getArtifactSha256(), po.getMetricsJson(), po.getReplayCommand(), po.getCreatedAt());
    }

    public List<CheckpointPO> findCheckpoints(long taskId) {
        return jdbcTemplate.query(
                "SELECT * FROM stage_checkpoint WHERE task_id = ? ORDER BY checkpoint_seq",
                CHECKPOINT_MAPPER, taskId);
    }

    public CheckpointPO findLatestCheckpoint(long taskId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM stage_checkpoint WHERE task_id = ? ORDER BY checkpoint_seq DESC LIMIT 1",
                    CHECKPOINT_MAPPER, taskId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 幂等请求 ----------------

    @Transactional
    public void insertRequest(RequestPO po) {
        jdbcTemplate.update(
                "INSERT INTO request_record (request_id, task_id, request_hash, request_json, created_at) "
                        + "VALUES (?,?,?,?,?)",
                po.getRequestId(), po.getTaskId(), po.getRequestHash(), po.getRequestJson(),
                po.getCreatedAt());
    }

    /** 任务行落库后回填幂等记录的任务ID。 */
    @Transactional
    public void bindRequestTask(String requestId, long taskId) {
        jdbcTemplate.update("UPDATE request_record SET task_id = ? WHERE request_id = ?",
                taskId, requestId);
    }

    public RequestPO findRequest(String requestId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM request_record WHERE request_id = ?",
                    REQUEST_MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 映射与辅助 ----------------

    private void setNullableString(PreparedStatement ps, int index, String value) throws java.sql.SQLException {
        if (value == null || value.isBlank()) {
            ps.setNull(index, java.sql.Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private void setNullableLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static final RowMapper<TaskPO> TASK_MAPPER = (rs, rowNum) -> {
        TaskPO po = new TaskPO();
        po.setId(rs.getLong("id"));
        po.setTaskCode(rs.getString("task_code"));
        po.setRequestId(rs.getString("request_id"));
        po.setStatus(rs.getString("status"));
        po.setCodeVersion(rs.getString("code_version"));
        po.setConfigVersion(rs.getString("config_version"));
        po.setMapVersion(rs.getString("map_version"));
        po.setRosbagVersion(rs.getString("rosbag_version"));
        po.setSeed(rs.getLong("seed"));
        po.setFailStage(rs.getString("fail_stage"));
        po.setFailTimes(rs.getInt("fail_times"));
        po.setInterruptAfter(rs.getString("interrupt_after"));
        po.setResultHash(rs.getString("result_hash"));
        po.setErrorMessage(rs.getString("error_message"));
        po.setStartedAt(getNullableLong(rs, "started_at"));
        po.setFinishedAt(getNullableLong(rs, "finished_at"));
        po.setCreatedAt(rs.getLong("created_at"));
        po.setUpdatedAt(rs.getLong("updated_at"));
        return po;
    };

    private static final RowMapper<StagePO> STAGE_MAPPER = (rs, rowNum) -> {
        StagePO po = new StagePO();
        po.setId(rs.getLong("id"));
        po.setTaskId(rs.getLong("task_id"));
        po.setStage(rs.getString("stage"));
        po.setSeqNo(rs.getInt("seq_no"));
        po.setStatus(rs.getString("status"));
        po.setAttempt(rs.getInt("attempt"));
        po.setStartedAt(getNullableLong(rs, "started_at"));
        po.setFinishedAt(getNullableLong(rs, "finished_at"));
        po.setMetricsJson(rs.getString("metrics_json"));
        po.setErrorMessage(rs.getString("error_message"));
        po.setArtifactPath(rs.getString("artifact_path"));
        po.setArtifactSha256(rs.getString("artifact_sha256"));
        po.setReplayCommand(rs.getString("replay_command"));
        return po;
    };

    private static final RowMapper<CheckpointPO> CHECKPOINT_MAPPER = (rs, rowNum) -> {
        CheckpointPO po = new CheckpointPO();
        po.setId(rs.getLong("id"));
        po.setTaskId(rs.getLong("task_id"));
        po.setStage(rs.getString("stage"));
        po.setCheckpointSeq(rs.getInt("checkpoint_seq"));
        po.setCodeVersion(rs.getString("code_version"));
        po.setConfigVersion(rs.getString("config_version"));
        po.setMapVersion(rs.getString("map_version"));
        po.setRosbagVersion(rs.getString("rosbag_version"));
        po.setSeed(rs.getLong("seed"));
        po.setUpstreamDigestsJson(rs.getString("upstream_digests_json"));
        po.setStageOutputDigest(rs.getString("stage_output_digest"));
        po.setArtifactPath(rs.getString("artifact_path"));
        po.setArtifactSha256(rs.getString("artifact_sha256"));
        po.setMetricsJson(rs.getString("metrics_json"));
        po.setReplayCommand(rs.getString("replay_command"));
        po.setCreatedAt(rs.getLong("created_at"));
        return po;
    };

    private static final RowMapper<RequestPO> REQUEST_MAPPER = (rs, rowNum) -> {
        RequestPO po = new RequestPO();
        po.setRequestId(rs.getString("request_id"));
        po.setTaskId(rs.getLong("task_id"));
        po.setRequestHash(rs.getString("request_hash"));
        po.setRequestJson(rs.getString("request_json"));
        po.setCreatedAt(rs.getLong("created_at"));
        return po;
    };

    private static Long getNullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
