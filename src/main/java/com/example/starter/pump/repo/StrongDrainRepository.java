package com.example.starter.pump.repo;

import com.example.starter.pump.web.dto.StrongDrainView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 强排候选/生效序列、闸门目标、到位回执、各站任务与工况轨迹仓储。
 *
 * <p>关键不变量（由数据库约束保证）：
 * <ul>
 *   <li>同一分区至多一条 ACTIVE 序列：确认时以条件 UPDATE 原子终结旧序列；</li>
 *   <li>候选序列＋站点闸门目标唯一（{@code uk_sdt_sequence_station}）；</li>
 *   <li>回执按“序列＋站点”唯一（{@code uk_gr_sequence_station}）：旧序列回执只归旧序列；</li>
 *   <li>强排任务按“站点＋UTC时刻”唯一（{@code uk_sdtask_station_time}）。</li>
 * </ul>
 */
@Repository
public class StrongDrainRepository {

    private final JdbcTemplate jdbc;

    public StrongDrainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ============ 序列 ============

    /** 创建候选序列并回填ID。 */
    public Long insertSequence(String zoneCode, long tideVersion, BigDecimal tideLevel,
                               String direction, int regimeVersion, Long expectedActiveSequenceId,
                               OffsetDateTime taskTime, LocalDateTime now) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    """
                    INSERT INTO strong_drain_sequence (zone_code, tide_version, tide_level,
                        direction, regime_version, expected_active_sequence_id, state,
                        task_time, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'CANDIDATE', ?, ?)
                    """,
                    new String[]{"id"});
            ps.setString(1, zoneCode);
            ps.setLong(2, tideVersion);
            ps.setBigDecimal(3, tideLevel);
            ps.setString(4, direction);
            ps.setInt(5, regimeVersion);
            if (expectedActiveSequenceId == null) {
                ps.setObject(6, null);
            } else {
                ps.setLong(6, expectedActiveSequenceId);
            }
            ps.setObject(7, taskTime);
            ps.setObject(8, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("强排序列自增ID回填失败");
        }
        return key.longValue();
    }

    /** 按ID查询序列行；不存在返回空。 */
    public Optional<StrongDrainView.SequenceRow> findSequenceById(Long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT id, zone_code, tide_version, tide_level, direction, regime_version,
                        expected_active_sequence_id, state, task_time, created_at
                    FROM strong_drain_sequence WHERE id = ?
                    """,
                    (rs, n) -> new StrongDrainView.SequenceRow(
                            rs.getLong("id"),
                            rs.getString("zone_code"),
                            rs.getLong("tide_version"),
                            rs.getBigDecimal("tide_level"),
                            rs.getString("direction"),
                            rs.getInt("regime_version"),
                            (Long) rs.getObject("expected_active_sequence_id"),
                            rs.getString("state"),
                            rs.getObject("task_time", OffsetDateTime.class),
                            rs.getObject("created_at", LocalDateTime.class)),
                    id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 行级锁定候选序列（SELECT ... FOR UPDATE），串行化同一候选的并发确认。 */
    public void lockSequence(Long id) {
        jdbc.queryForObject(
                "SELECT id FROM strong_drain_sequence WHERE id = ? FOR UPDATE",
                Long.class, id);
    }

    /** 分区当前 ACTIVE 序列ID（不存在返回空）。 */
    public Optional<Long> findActiveSequenceId(String zoneCode) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM strong_drain_sequence WHERE zone_code = ? AND state = 'ACTIVE' ORDER BY id DESC LIMIT 1",
                (rs, n) -> rs.getLong("id"), zoneCode);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /**
     * 条件终结旧 ACTIVE 序列：仅当其ID等于期望ID时置 SUPERSEDED。
     *
     * @return 受影响行数（0 表示旧序列版本已被并发切换改变）
     */
    public int supersedeIfActive(String zoneCode, Long expectedActiveId) {
        if (expectedActiveId == null) {
            Integer rows = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM strong_drain_sequence WHERE zone_code = ? AND state = 'ACTIVE'",
                    Integer.class, zoneCode);
            return rows != null && rows == 0 ? 1 : 0;
        }
        return jdbc.update(
                """
                UPDATE strong_drain_sequence SET state = 'SUPERSEDED'
                WHERE zone_code = ? AND state = 'ACTIVE' AND id = ?
                """,
                zoneCode, expectedActiveId);
    }

    /** 候选条件生效：仅当仍为 CANDIDATE 时置 ACTIVE，返回受影响行数。 */
    public int activateIfCandidate(Long sequenceId) {
        return jdbc.update(
                "UPDATE strong_drain_sequence SET state = 'ACTIVE' WHERE id = ? AND state = 'CANDIDATE'",
                sequenceId);
    }

    /** 退出候选条件完成：仅当仍为 CANDIDATE 时置 COMPLETED 终态，返回受影响行数。 */
    public int completeIfCandidate(Long sequenceId) {
        return jdbc.update(
                "UPDATE strong_drain_sequence SET state = 'COMPLETED' WHERE id = ? AND state = 'CANDIDATE'",
                sequenceId);
    }

    /** 分区当前未确认的候选（方向取最新一条）；用于跨潮位不重复生成同方向候选。 */
    public Optional<StrongDrainView.SequenceRow> findOpenCandidate(String zoneCode) {
        List<StrongDrainView.SequenceRow> list = jdbc.query(
                """
                SELECT id, zone_code, tide_version, tide_level, direction, regime_version,
                    expected_active_sequence_id, state, task_time, created_at
                FROM strong_drain_sequence
                WHERE zone_code = ? AND state = 'CANDIDATE' ORDER BY id DESC LIMIT 1
                """,
                SEQUENCE_ROW_MAPPER, zoneCode);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private static final org.springframework.jdbc.core.RowMapper<StrongDrainView.SequenceRow>
            SEQUENCE_ROW_MAPPER = (rs, n) -> new StrongDrainView.SequenceRow(
            rs.getLong("id"),
            rs.getString("zone_code"),
            rs.getLong("tide_version"),
            rs.getBigDecimal("tide_level"),
            rs.getString("direction"),
            rs.getInt("regime_version"),
            (Long) rs.getObject("expected_active_sequence_id"),
            rs.getString("state"),
            rs.getObject("task_time", OffsetDateTime.class),
            rs.getObject("created_at", LocalDateTime.class));

    // ============ 闸门目标 ============

    /** 固化候选序列某站闸门目标。 */
    public void insertTarget(Long sequenceId, String stationCode, BigDecimal gateTarget) {
        jdbc.update(
                """
                INSERT INTO strong_drain_target (sequence_id, station_code, gate_target)
                VALUES (?, ?, ?)
                """,
                sequenceId, stationCode, gateTarget);
    }

    /** 候选序列固化的全部站闸门目标。 */
    public List<StrongDrainView.TargetItem> findTargets(Long sequenceId) {
        return jdbc.query(
                """
                SELECT station_code, gate_target FROM strong_drain_target
                WHERE sequence_id = ? ORDER BY station_code
                """,
                (rs, n) -> new StrongDrainView.TargetItem(
                        rs.getString("station_code"), rs.getBigDecimal("gate_target")),
                sequenceId);
    }

    // ============ 到位回执 ============

    /**
     * 写入闸门到位回执（按序列＋站点唯一）。
     *
     * @return true 首次写入；false 该序列该站已有回执
     */
    public boolean insertReceipt(Long sequenceId, String stationCode, BigDecimal opening,
                                 LocalDateTime now) {
        try {
            jdbc.update(
                    """
                    INSERT INTO gate_receipt (sequence_id, station_code, opening, received_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    sequenceId, stationCode, opening, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 序列全部到位回执。 */
    public List<StrongDrainView.ReceiptItem> findReceipts(Long sequenceId) {
        return jdbc.query(
                """
                SELECT station_code, opening, received_at FROM gate_receipt
                WHERE sequence_id = ? ORDER BY station_code
                """,
                (rs, n) -> new StrongDrainView.ReceiptItem(
                        rs.getString("station_code"),
                        rs.getBigDecimal("opening"),
                        rs.getObject("received_at", LocalDateTime.class)),
                sequenceId);
    }

    // ============ 各站启停任务 ============

    /**
     * 写各站强排启停任务（确认时同事务写出）。
     *
     * @return 任务ID；站点＋UTC时刻冲突返回 null
     */
    public Long insertTask(Long sequenceId, String stationCode, String action,
                           OffsetDateTime taskTime, String reason) {
        try {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                java.sql.PreparedStatement ps = con.prepareStatement(
                        """
                        INSERT INTO strong_drain_task (sequence_id, station_code, action,
                            task_time, reason, task_state)
                        VALUES (?, ?, ?, ?, ?, 'ACKED')
                        """,
                        new String[]{"id"});
                ps.setLong(1, sequenceId);
                ps.setString(2, stationCode);
                ps.setString(3, action);
                ps.setObject(4, taskTime);
                ps.setString(5, reason);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("强排任务自增ID回填失败");
            }
            return key.longValue();
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    /** 序列全部启停任务。 */
    public List<StrongDrainView.TaskItem> findTasks(Long sequenceId) {
        return jdbc.query(
                """
                SELECT id, station_code, action, task_time, reason, task_state
                FROM strong_drain_task WHERE sequence_id = ?
                ORDER BY task_time, id
                """,
                (rs, n) -> new StrongDrainView.TaskItem(
                        rs.getLong("id"),
                        rs.getString("station_code"),
                        rs.getString("action"),
                        rs.getObject("task_time", OffsetDateTime.class),
                        rs.getString("reason"),
                        rs.getString("task_state")),
                sequenceId);
    }

    /** 某站最近一条强排任务动作（无则空）。 */
    public Optional<String> latestTaskAction(String stationCode) {
        List<String> actions = jdbc.query(
                """
                SELECT action FROM strong_drain_task
                WHERE station_code = ? ORDER BY task_time DESC, id DESC LIMIT 1
                """,
                (rs, n) -> rs.getString("action"), stationCode);
        return actions.isEmpty() ? Optional.empty() : Optional.of(actions.get(0));
    }

    /** 某站指定时刻之前（不含）最近一次强排启泵时刻。 */
    public Optional<OffsetDateTime> latestStrongStartTimeBefore(String stationCode,
                                                                OffsetDateTime before) {
        List<OffsetDateTime> times = jdbc.query(
                """
                SELECT task_time FROM strong_drain_task
                WHERE station_code = ? AND action = 'START' AND task_time < ?
                ORDER BY task_time DESC, id DESC LIMIT 1
                """,
                (rs, n) -> rs.getObject("task_time", OffsetDateTime.class),
                stationCode, before);
        return times.isEmpty() ? Optional.empty() : Optional.of(times.get(0));
    }

    /** 原预降体系中某站最近一条启停动作（跨任务体系检查物理占用与启机间隔）。 */
    public Optional<String> latestOriginalTaskAction(String stationCode) {
        List<String> actions = jdbc.query(
                """
                SELECT action FROM pump_task
                WHERE station_code = ? ORDER BY task_time DESC, id DESC LIMIT 1
                """,
                (rs, n) -> rs.getString("action"), stationCode);
        return actions.isEmpty() ? Optional.empty() : Optional.of(actions.get(0));
    }

    /** 原预降体系中某站指定时刻前最近一次启泵时刻。 */
    public Optional<LocalDateTime> latestOriginalStartTimeBefore(String stationCode,
                                                                 LocalDateTime before) {
        List<LocalDateTime> times = jdbc.query(
                """
                SELECT task_time FROM pump_task
                WHERE station_code = ? AND action = 'START' AND task_time < ?
                ORDER BY task_time DESC, id DESC LIMIT 1
                """,
                (rs, n) -> rs.getObject("task_time", LocalDateTime.class),
                stationCode, before);
        return times.isEmpty() ? Optional.empty() : Optional.of(times.get(0));
    }

    // ============ 工况轨迹 ============

    /** 追加工况轨迹（只追加）。 */
    public void insertTrace(String zoneCode, Long tideVersion, String eventType,
                            String fromMode, String toMode, Long sequenceId, String detail,
                            LocalDateTime now) {
        jdbc.update(
                """
                INSERT INTO regime_trace (zone_code, tide_version, event_type, from_mode, to_mode,
                    sequence_id, detail, event_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                zoneCode, tideVersion, eventType, fromMode, toMode, sequenceId, detail, now);
    }

    /** 分区工况轨迹，按时间、ID升序。 */
    public List<StrongDrainView.TraceItem> findTrace(String zoneCode) {
        return jdbc.query(
                """
                SELECT id, zone_code, tide_version, event_type, from_mode, to_mode,
                    sequence_id, detail, event_time
                FROM regime_trace WHERE zone_code = ? ORDER BY id
                """,
                (rs, n) -> new StrongDrainView.TraceItem(
                        rs.getLong("id"),
                        rs.getString("zone_code"),
                        (Long) rs.getObject("tide_version"),
                        rs.getString("event_type"),
                        rs.getString("from_mode"),
                        rs.getString("to_mode"),
                        (Long) rs.getObject("sequence_id"),
                        rs.getString("detail"),
                        rs.getObject("event_time", LocalDateTime.class)),
                zoneCode);
    }
}
