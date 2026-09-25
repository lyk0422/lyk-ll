package com.example.starter.repository;

import com.example.starter.domain.AdjustmentTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 调节任务数据访问。
 */
@Repository
public class AdjustmentTaskRepository {

    private static final RowMapper<AdjustmentTask> MAPPER = (rs, n) -> new AdjustmentTask(
            rs.getLong("id"), rs.getLong("station_id"), rs.getLong("unit_id"),
            rs.getString("request_id"), rs.getString("fingerprint"),
            Optional.ofNullable(rs.getString("classification"))
                    .map(AdjustmentTask.ImbalanceType::valueOf).orElse(null),
            rs.getBigDecimal("step_percent"), rs.getBigDecimal("old_opening"),
            rs.getBigDecimal("new_opening"),
            AdjustmentTask.TaskStatus.valueOf(rs.getString("status")),
            Optional.ofNullable(rs.getTimestamp("executed_at")).map(Timestamp::toInstant).orElse(null),
            Optional.ofNullable(rs.getTimestamp("assessed_at")).map(Timestamp::toInstant).orElse(null),
            Optional.ofNullable(rs.getTimestamp("archived_at")).map(Timestamp::toInstant).orElse(null),
            rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS = "id, station_id, unit_id, request_id, fingerprint,"
            + " classification, step_percent, old_opening, new_opening, status,"
            + " executed_at, assessed_at, archived_at, created_at";

    private final JdbcTemplate jdbc;

    public AdjustmentTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入任务并返回生成的主键。
     */
    public long insert(AdjustmentTask task) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO adjustment_task"
                            + " (station_id, unit_id, request_id, fingerprint, classification,"
                            + " step_percent, old_opening, new_opening, status,"
                            + " executed_at, assessed_at, archived_at, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, task.stationId());
            ps.setLong(2, task.unitId());
            ps.setString(3, task.requestId());
            ps.setString(4, task.fingerprint());
            ps.setString(5, task.classification() == null ? null : task.classification().name());
            ps.setBigDecimal(6, task.stepPercent());
            ps.setBigDecimal(7, task.oldOpening());
            ps.setBigDecimal(8, task.newOpening());
            ps.setString(9, task.status().name());
            ps.setTimestamp(10, task.executedAt() == null ? null : Timestamp.from(task.executedAt()));
            ps.setTimestamp(11, task.assessedAt() == null ? null : Timestamp.from(task.assessedAt()));
            ps.setTimestamp(12, task.archivedAt() == null ? null : Timestamp.from(task.archivedAt()));
            ps.setTimestamp(13, Timestamp.from(task.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等键查询任务。
     */
    public Optional<AdjustmentTask> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM adjustment_task WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 按 ID 查询任务。
     */
    public Optional<AdjustmentTask> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM adjustment_task WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 站内是否存在执行中（EXECUTING/ASSESSED）任务。
     */
    public boolean existsActiveByStationId(long stationId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_task"
                        + " WHERE station_id = ? AND status IN ('EXECUTING', 'ASSESSED')",
                Integer.class, stationId);
        return count != null && count > 0;
    }

    /**
     * 统计单元在指定时间点之后已执行的调节次数（用于 24 小时窗口限制）。
     */
    public int countExecutedSince(long unitId, Instant since) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_task"
                        + " WHERE unit_id = ? AND executed_at IS NOT NULL AND executed_at >= ?",
                Integer.class, unitId, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    /**
     * 查询单元最近一次已归档任务（用于迟到温度判定）。
     */
    public Optional<AdjustmentTask> findLatestArchivedByStationId(long stationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM adjustment_task"
                        + " WHERE station_id = ? AND status = 'ARCHIVED'"
                        + " ORDER BY archived_at DESC LIMIT 1",
                MAPPER, stationId).stream().findFirst();
    }

    /**
     * 更新任务状态及对应时间戳。
     */
    public void updateStatus(long taskId, AdjustmentTask.TaskStatus status,
                             Instant assessedAt, Instant archivedAt) {
        jdbc.update("UPDATE adjustment_task SET status = ?,"
                        + " assessed_at = COALESCE(?, assessed_at),"
                        + " archived_at = COALESCE(?, archived_at) WHERE id = ?",
                status.name(),
                assessedAt == null ? null : Timestamp.from(assessedAt),
                archivedAt == null ? null : Timestamp.from(archivedAt),
                taskId);
    }

    /**
     * 按单元查询任务历史，按主键升序。
     */
    public List<AdjustmentTask> findByUnitId(long unitId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM adjustment_task WHERE unit_id = ? ORDER BY id",
                MAPPER, unitId);
    }
}
