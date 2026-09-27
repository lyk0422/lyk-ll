package com.example.starter.pump.repo;

import com.example.starter.pump.web.dto.ReplayResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 泵站启停任务与启停序列仓储。
 *
 * <p>并发约束由数据库唯一索引保证：
 * <ul>
 *   <li>{@code uk_zone_time_sequence(pumpdown_id, zone_code, task_time)}：同一分区同一时刻仅一套序列；</li>
 *   <li>{@code uk_station_time(station_code, task_time)}：同一泵站同一时刻仅一个启停任务。</li>
 * </ul>
 */
@Repository
public class PumpTaskRepository {

    private final JdbcTemplate jdbc;

    public PumpTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查找或创建某分区某时刻的联动序列：同分区同时刻并发指令归入同一套序列。
     */
    public Long findOrCreateSequence(Long pumpdownId, String zoneCode, LocalDateTime taskTime,
                                     LocalDateTime now) {
        Long existing = findSequenceId(pumpdownId, zoneCode, taskTime);
        if (existing != null) {
            return existing;
        }
        try {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                java.sql.PreparedStatement ps = con.prepareStatement(
                        """
                        INSERT INTO pump_sequence (pumpdown_id, zone_code, task_time, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                        new String[]{"id"});
                ps.setLong(1, pumpdownId);
                ps.setString(2, zoneCode);
                ps.setObject(3, taskTime);
                ps.setObject(4, now);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("启停序列自增ID回填失败");
            }
            return key.longValue();
        } catch (DuplicateKeyException e) {
            Long concurrent = findSequenceId(pumpdownId, zoneCode, taskTime);
            if (concurrent != null) {
                return concurrent;
            }
            throw e;
        }
    }

    private Long findSequenceId(Long pumpdownId, String zoneCode, LocalDateTime taskTime) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM pump_sequence WHERE pumpdown_id = ? AND zone_code = ? AND task_time = ?",
                    Long.class, pumpdownId, zoneCode, taskTime);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 插入单站启停任务。
     *
     * @return 任务ID；同站同时刻冲突时返回 null（并发指令唯一受理）
     */
    public Long insertTask(Long sequenceId, Long pumpdownId, String stationCode,
                           String action, LocalDateTime taskTime, String reason) {
        try {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                java.sql.PreparedStatement ps = con.prepareStatement(
                        """
                        INSERT INTO pump_task (sequence_id, pumpdown_id, station_code, action,
                            task_time, reason) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        new String[]{"id"});
                ps.setLong(1, sequenceId);
                ps.setLong(2, pumpdownId);
                ps.setString(3, stationCode);
                ps.setString(4, action);
                ps.setObject(5, taskTime);
                ps.setString(6, reason);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("启停任务自增ID回填失败");
            }
            return key.longValue();
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    /** 写入闸门开度到位日志，须与启泵任务同事务提交。 */
    public void insertGateLog(Long pumpTaskId, String stationCode, BigDecimal opening,
                              LocalDateTime at) {
        jdbc.update(
                """
                INSERT INTO gate_opening_log (pump_task_id, station_code, opening, updated_at)
                VALUES (?, ?, ?, ?)
                """,
                pumpTaskId, stationCode, opening, at);
    }

    /**
     * 查询某泵站在指定时刻之前（不含）最近一次启泵时刻，用于启机间隔校验。
     */
    public LocalDateTime latestStartTimeBefore(String stationCode, LocalDateTime taskTime) {
        List<LocalDateTime> times = jdbc.query(
                """
                SELECT task_time FROM pump_task
                WHERE station_code = ? AND action = 'START' AND task_time < ?
                ORDER BY task_time DESC LIMIT 1
                """,
                (rs, n) -> rs.getObject("task_time", LocalDateTime.class),
                stationCode, taskTime);
        return times.isEmpty() ? null : times.get(0);
    }

    /** 最近一条任务记录（动作+时刻），用于判断泵当前运行态。 */
    public LastTask latestTask(String stationCode, Long pumpdownId) {
        List<LastTask> list = jdbc.query(
                """
                SELECT action, task_time FROM pump_task
                WHERE station_code = ? AND pumpdown_id = ?
                ORDER BY task_time DESC, id DESC LIMIT 1
                """,
                (rs, n) -> new LastTask(rs.getString("action"),
                        rs.getObject("task_time", LocalDateTime.class)),
                stationCode, pumpdownId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 最近任务摘要。 */
    public record LastTask(String action, LocalDateTime taskTime) {
        public boolean running() {
            return "START".equals(action);
        }
    }

    /** 某泵站在指定时刻是否已存在启停任务（占用校验）。 */
    public boolean taskExistsAt(String stationCode, LocalDateTime taskTime) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM pump_task WHERE station_code = ? AND task_time = ?",
                Integer.class, stationCode, taskTime);
        return cnt != null && cnt > 0;
    }

    /** 终结分区在指定预降事件下全部生效中的旧序列（强排切换原子启用新序列前调用）。 */
    public void terminateActiveSequences(Long pumpdownId, String zoneCode,
                                         LocalDateTime terminatedAt) {
        jdbc.update(
                """
                UPDATE pump_sequence SET status = 'TERMINATED', terminated_at = ?
                WHERE pumpdown_id = ? AND zone_code = ? AND status = 'ACTIVE'
                """,
                terminatedAt, pumpdownId, zoneCode);
    }

    /** 启用序列为当前生效序列（强排切换原子启用新序列）。 */
    public void activateSequence(Long sequenceId) {
        jdbc.update(
                "UPDATE pump_sequence SET status = 'ACTIVE', terminated_at = NULL WHERE id = ?",
                sequenceId);
    }

    /** 复算：预降事件的启停序列（LEFT JOIN 闸门开度）。 */
    public List<ReplayResult.TaskItem> loadTasks(Long pumpdownId) {
        return jdbc.query(
                """
                SELECT t.id AS task_id, t.station_code, t.action, t.task_time, t.reason,
                       g.opening AS gate_opening
                FROM pump_task t
                LEFT JOIN gate_opening_log g ON g.pump_task_id = t.id
                WHERE t.pumpdown_id = ?
                ORDER BY t.task_time, t.id
                """,
                (rs, n) -> new ReplayResult.TaskItem(
                        rs.getLong("task_id"),
                        rs.getString("station_code"),
                        rs.getString("action"),
                        rs.getObject("task_time", LocalDateTime.class),
                        rs.getString("reason"),
                        rs.getBigDecimal("gate_opening")),
                pumpdownId);
    }
}
