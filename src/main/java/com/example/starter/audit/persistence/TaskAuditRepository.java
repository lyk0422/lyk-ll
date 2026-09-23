package com.example.starter.audit.persistence;

import com.example.starter.audit.model.Stage;
import com.example.starter.audit.model.StageState;
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
import java.util.Optional;

/**
 * 任务、阶段、检查点三类审计记录的 JDBC 仓库（H2 MODE=MySQL）。
 * 所有写操作均使用参数化 SQL；时间列以 UTC 时间戳存储。
 */
@Repository
public class TaskAuditRepository {

    private static final RowMapper<TaskRow> TASK_MAPPER = (rs, n) -> new TaskRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("request_hash"),
            rs.getString("code_version"),
            rs.getString("config_version"),
            rs.getString("map_version"),
            rs.getString("rosbag_version"),
            rs.getLong("seed"),
            rs.getString("metrics_profile"),
            rs.getString("fault_plan"),
            rs.getString("state"),
            rs.getString("result_hash"),
            rs.getString("error_message"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("finished_at")));

    private static final RowMapper<StageRow> STAGE_MAPPER = (rs, n) -> new StageRow(
            rs.getLong("id"),
            rs.getLong("task_id"),
            rs.getString("stage"),
            rs.getInt("attempt"),
            rs.getString("state"),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("finished_at")),
            rs.getString("metrics_json"),
            rs.getString("error_message"),
            rs.getString("artifact_path"),
            rs.getString("artifact_sha256"),
            rs.getString("output_digest"),
            rs.getString("replay_command"));

    private static final RowMapper<CheckpointRow> CHECKPOINT_MAPPER = (rs, n) -> new CheckpointRow(
            rs.getLong("id"),
            rs.getLong("task_id"),
            rs.getString("stage"),
            rs.getInt("attempt"),
            rs.getString("code_version"),
            rs.getString("config_version"),
            rs.getString("map_version"),
            rs.getString("rosbag_version"),
            rs.getLong("seed"),
            rs.getString("upstream_digest"),
            rs.getString("output_digest"),
            rs.getString("artifact_sha256"),
            rs.getString("metrics_json"),
            toInstant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public TaskAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertTask(String requestId, String requestHash, TaskBindingsSnapshot b) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO task_run (request_id, request_hash, code_version, config_version, "
                            + "map_version, rosbag_version, seed, metrics_profile, fault_plan, "
                            + "state, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            if (requestId == null) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, requestId);
            }
            ps.setString(2, requestHash);
            ps.setString(3, b.codeVersion());
            ps.setString(4, b.configVersion());
            ps.setString(5, b.mapVersion());
            ps.setString(6, b.rosbagVersion());
            ps.setLong(7, b.seed());
            ps.setString(8, b.metricsProfile());
            if (b.faultPlan() == null) {
                ps.setNull(9, Types.VARCHAR);
            } else {
                ps.setString(9, b.faultPlan());
            }
            ps.setTimestamp(10, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    public Optional<TaskRow> findTaskById(long taskId) {
        return jdbc.query("SELECT * FROM task_run WHERE id = ?", TASK_MAPPER, taskId)
                .stream().findFirst();
    }

    public Optional<TaskRow> findTaskByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM task_run WHERE request_id = ?", TASK_MAPPER, requestId)
                .stream().findFirst();
    }

    public void markTaskRunning(long taskId, Instant at) {
        jdbc.update("UPDATE task_run SET state = 'RUNNING', started_at = ? "
                + "WHERE id = ? AND started_at IS NULL", Timestamp.from(at), taskId);
    }

    public void markTaskFinished(long taskId, String state, String resultHash,
                                 String errorMessage, Instant at) {
        jdbc.update("UPDATE task_run SET state = ?, result_hash = ?, error_message = ?, "
                        + "finished_at = ? WHERE id = ?",
                ps -> {
                    ps.setString(1, state);
                    if (resultHash == null) {
                        ps.setNull(2, Types.VARCHAR);
                    } else {
                        ps.setString(2, resultHash);
                    }
                    if (errorMessage == null) {
                        ps.setNull(3, Types.CLOB);
                    } else {
                        ps.setString(3, errorMessage);
                    }
                    ps.setTimestamp(4, Timestamp.from(at));
                    ps.setLong(5, taskId);
                });
    }

    public long insertStage(long taskId, Stage stage, int attempt) {
        jdbc.update("INSERT INTO task_stage (task_id, stage, attempt, state) "
                + "VALUES (?, ?, ?, 'PENDING')", taskId, stage.getCode(), attempt);
        Long id = jdbc.queryForObject(
                "SELECT id FROM task_stage WHERE task_id = ? AND stage = ? AND attempt = ?",
                Long.class, taskId, stage.getCode(), attempt);
        return id == null ? -1L : id;
    }

    public void markStageRunning(long stageId, Instant at) {
        jdbc.update("UPDATE task_stage SET state = 'RUNNING', started_at = ? WHERE id = ?",
                Timestamp.from(at), stageId);
    }

    public void markStageSuccess(long stageId, StageSuccessData data, Instant at) {
        jdbc.update("UPDATE task_stage SET state = 'SUCCESS', finished_at = ?, metrics_json = ?, "
                        + "artifact_path = ?, artifact_sha256 = ?, output_digest = ?, "
                        + "replay_command = ?, error_message = NULL WHERE id = ?",
                ps -> {
                    ps.setTimestamp(1, Timestamp.from(at));
                    ps.setString(2, data.metricsJson());
                    ps.setString(3, data.artifactPath());
                    ps.setString(4, data.artifactSha256());
                    ps.setString(5, data.outputDigest());
                    ps.setString(6, data.replayCommand());
                    ps.setLong(7, stageId);
                });
    }

    public void markStageTerminal(long stageId, StageState state, String errorMessage, Instant at) {
        jdbc.update("UPDATE task_stage SET state = ?, finished_at = ?, error_message = ? "
                + "WHERE id = ?", state.name(), Timestamp.from(at), errorMessage, stageId);
    }

    public List<StageRow> listStages(long taskId) {
        return jdbc.query("SELECT * FROM task_stage WHERE task_id = ? "
                + "ORDER BY attempt ASC, id ASC", STAGE_MAPPER, taskId);
    }

    /** 每个阶段只保留最新批次视图时使用，按阶段声明顺序返回。 */
    public List<StageRow> listLatestStages(long taskId) {
        return jdbc.query("SELECT s.* FROM task_stage s "
                + "JOIN (SELECT stage, MAX(attempt) AS max_attempt FROM task_stage "
                + "WHERE task_id = ? GROUP BY stage) m "
                + "ON s.stage = m.stage AND s.attempt = m.max_attempt "
                + "WHERE s.task_id = ? ORDER BY s.id ASC", STAGE_MAPPER, taskId, taskId);
    }

    public Optional<StageRow> findLatestStage(long taskId, Stage stage) {
        return jdbc.query("SELECT * FROM task_stage WHERE task_id = ? AND stage = ? "
                        + "ORDER BY attempt DESC FETCH FIRST 1 ROWS ONLY",
                STAGE_MAPPER, taskId, stage.getCode()).stream().findFirst();
    }

    public void insertCheckpoint(CheckpointRow row) {
        upsertCheckpoint(row);
    }

    /** 按 (task_id, stage) 唯一键写入或覆盖检查点（恢复重跑后刷新该阶段检查点）。 */
    public void upsertCheckpoint(CheckpointRow row) {
        jdbc.update("MERGE INTO task_checkpoint (task_id, stage, attempt, code_version, "
                        + "config_version, map_version, rosbag_version, seed, upstream_digest, "
                        + "output_digest, artifact_sha256, metrics_json, created_at) "
                        + "KEY (task_id, stage) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, row.taskId());
                    ps.setString(2, row.stage());
                    ps.setInt(3, row.attempt());
                    ps.setString(4, row.codeVersion());
                    ps.setString(5, row.configVersion());
                    ps.setString(6, row.mapVersion());
                    ps.setString(7, row.rosbagVersion());
                    ps.setLong(8, row.seed());
                    ps.setString(9, row.upstreamDigest());
                    ps.setString(10, row.outputDigest());
                    ps.setString(11, row.artifactSha256());
                    ps.setString(12, row.metricsJson());
                    ps.setTimestamp(13, Timestamp.from(row.createdAt()));
                });
    }

    public List<CheckpointRow> listCheckpoints(long taskId) {
        return jdbc.query("SELECT * FROM task_checkpoint WHERE task_id = ? ORDER BY id ASC",
                CHECKPOINT_MAPPER, taskId);
    }

    /** 最近一次成功检查点（按阶段链与批次取最后一条）。 */
    public Optional<CheckpointRow> findLatestCheckpoint(long taskId) {
        return jdbc.query("SELECT * FROM task_checkpoint WHERE task_id = ? "
                + "ORDER BY id DESC FETCH FIRST 1 ROWS ONLY", CHECKPOINT_MAPPER, taskId)
                .stream().findFirst();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 建表时插入任务行用的绑定版本与模拟参数快照。 */
    public record TaskBindingsSnapshot(
            String codeVersion, String configVersion, String mapVersion,
            String rosbagVersion, long seed, String metricsProfile, String faultPlan) {
    }

    /** 阶段成功落库数据。 */
    public record StageSuccessData(
            String metricsJson, String artifactPath, String artifactSha256,
            String outputDigest, String replayCommand) {
    }
}
