package com.example.starter.repo;

import com.example.starter.domain.TaskStatus;
import com.example.starter.po.AdjustmentTask;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 调节任务数据访问。
 *
 * <p>{@code active_station_id} 列上的唯一约束（多列 NULL 不冲突）保证：
 * 同一换热站同时刻只有一个执行中任务；归档后置空以释放站点。</p>
 */
@Repository
public class TaskRepository {

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AdjustmentTask> MAPPER = (rs, n) -> {
        AdjustmentTask t = new AdjustmentTask();
        t.setId(rs.getLong("id"));
        t.setStationId(rs.getLong("station_id"));
        t.setRequestId(rs.getString("request_id"));
        t.setStatus(TaskStatus.valueOf(rs.getString("status")));
        long active = rs.getLong("active_station_id");
        t.setActiveStationId(rs.wasNull() ? null : active);
        Timestamp archived = rs.getTimestamp("archived_at");
        t.setArchivedAt(archived == null ? null : archived.toInstant());
        t.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return t;
    };

    private static final String COLUMNS =
            "SELECT id, station_id, request_id, status, active_station_id, created_at, archived_at FROM adjustment_task ";

    /**
     * 创建执行中任务。若该换热站已存在执行中任务（唯一约束冲突），返回 null。
     */
    public AdjustmentTask createExecuting(long stationId, String requestId, Instant now) {
        try {
            jdbc.update("INSERT INTO adjustment_task (station_id, request_id, status, active_station_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    stationId, requestId, TaskStatus.EXECUTING.name(), stationId, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            return null;
        }
        return findByStationActive(stationId);
    }

    public AdjustmentTask findByStationActive(long stationId) {
        List<AdjustmentTask> list = jdbc.query(
                COLUMNS + "WHERE active_station_id = ?", MAPPER, stationId);
        return list.isEmpty() ? null : list.get(0);
    }

    public AdjustmentTask findByStationAny(long stationId) {
        List<AdjustmentTask> list = jdbc.query(
                COLUMNS + "WHERE station_id = ? ORDER BY id DESC LIMIT 1", MAPPER, stationId);
        return list.isEmpty() ? null : list.get(0);
    }

    public AdjustmentTask findById(long taskId) {
        List<AdjustmentTask> list = jdbc.query(COLUMNS + "WHERE id = ?", MAPPER, taskId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 归档：置终态并释放 active_station_id，仅执行中任务可归档。
     *
     * @return 受影响行数（0 表示已归档或不存在）
     */
    public int archive(long taskId, Instant now) {
        return jdbc.update("UPDATE adjustment_task SET status = ?, active_station_id = NULL, archived_at = ? "
                        + "WHERE id = ? AND status = ?",
                TaskStatus.ARCHIVED.name(), Timestamp.from(now), taskId, TaskStatus.EXECUTING.name());
    }
}
