package com.example.starter.pump.repo;

import com.example.starter.pump.domain.GateReceipt;
import com.example.starter.pump.domain.GateTarget;
import com.example.starter.pump.domain.SwitchCandidate;
import com.example.starter.pump.domain.TrajectoryRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 工况切换仓储：候选序列、固定闸门目标、到位回执与工况轨迹。
 *
 * <p>回执按候选+站点唯一；候选状态流转以 {@code WHERE status = 'PENDING'} 守卫，
 * 并发确认时只有一个事务能翻转状态。
 */
@Repository
public class ModeSwitchRepository {

    private static final RowMapper<SwitchCandidate> CANDIDATE_MAPPER = (rs, n) -> new SwitchCandidate(
            rs.getLong("id"),
            rs.getString("zone_code"),
            rs.getString("direction"),
            rs.getLong("tide_reading_id"),
            rs.getObject("tide_time", LocalDateTime.class),
            rs.getBigDecimal("tide_level"),
            rs.getInt("pinned_version"),
            rs.getString("status"),
            rs.getObject("created_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public ModeSwitchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 候选 ----------

    /** 生成候选并回填自增ID，初始状态 PENDING。 */
    public Long insertCandidate(String zoneCode, String direction, Long tideReadingId,
                                LocalDateTime tideTime, BigDecimal tideLevel, int pinnedVersion,
                                LocalDateTime now) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    """
                    INSERT INTO mode_switch_candidate (zone_code, direction, tide_reading_id,
                        tide_time, tide_level, pinned_version, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                    """,
                    new String[]{"id"});
            ps.setString(1, zoneCode);
            ps.setString(2, direction);
            ps.setLong(3, tideReadingId);
            ps.setObject(4, tideTime);
            ps.setBigDecimal(5, tideLevel);
            ps.setInt(6, pinnedVersion);
            ps.setObject(7, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("切换候选自增ID回填失败");
        }
        return key.longValue();
    }

    /** 按ID查询候选，不存在返回空。 */
    public Optional<SwitchCandidate> findCandidate(Long candidateId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT id, zone_code, direction, tide_reading_id, tide_time, tide_level,
                        pinned_version, status, created_at
                    FROM mode_switch_candidate WHERE id = ?
                    """,
                    CANDIDATE_MAPPER, candidateId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 分区当前待确认候选（最多一个），无则返回空。 */
    public Optional<SwitchCandidate> findPendingByZone(String zoneCode) {
        List<SwitchCandidate> list = jdbc.query(
                """
                SELECT id, zone_code, direction, tide_reading_id, tide_time, tide_level,
                    pinned_version, status, created_at
                FROM mode_switch_candidate
                WHERE zone_code = ? AND status = 'PENDING'
                ORDER BY id DESC LIMIT 1
                """,
                CANDIDATE_MAPPER, zoneCode);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 将待确认候选翻转为目标状态（PENDING 守卫，并发下仅一个事务成功）。
     *
     * @return true 翻转成功；false 候选已不在待确认态
     */
    public boolean transitionFromPending(Long candidateId, String toStatus) {
        int rows = jdbc.update(
                "UPDATE mode_switch_candidate SET status = ? WHERE id = ? AND status = 'PENDING'",
                toStatus, candidateId);
        return rows == 1;
    }

    // ---------- 固定闸门目标 ----------

    /** 写入候选固定的单站闸门目标。 */
    public void insertTarget(Long candidateId, String stationCode, BigDecimal targetOpening) {
        jdbc.update(
                """
                INSERT INTO switch_gate_target (candidate_id, station_code, target_opening)
                VALUES (?, ?, ?)
                """,
                candidateId, stationCode, targetOpening);
    }

    /** 候选固定的全部闸门目标，按站点代码排序。 */
    public List<GateTarget> listTargets(Long candidateId) {
        return jdbc.query(
                """
                SELECT id, candidate_id, station_code, target_opening FROM switch_gate_target
                WHERE candidate_id = ? ORDER BY station_code
                """,
                (rs, n) -> new GateTarget(
                        rs.getLong("id"),
                        rs.getLong("candidate_id"),
                        rs.getString("station_code"),
                        rs.getBigDecimal("target_opening")),
                candidateId);
    }

    // ---------- 闸门到位回执 ----------

    /**
     * 写入闸门到位回执。
     *
     * @return 回执ID；同候选同站点重复返回 null（去重）
     */
    public Long insertReceipt(Long candidateId, String stationCode, BigDecimal opening,
                              LocalDateTime receivedAt) {
        try {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                java.sql.PreparedStatement ps = con.prepareStatement(
                        """
                        INSERT INTO switch_gate_receipt (candidate_id, station_code, opening,
                            received_at) VALUES (?, ?, ?, ?)
                        """,
                        new String[]{"id"});
                ps.setLong(1, candidateId);
                ps.setString(2, stationCode);
                ps.setBigDecimal(3, opening);
                ps.setObject(4, receivedAt);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("闸门回执自增ID回填失败");
            }
            return key.longValue();
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    /** 候选已收到的全部回执，按站点代码排序。 */
    public List<GateReceipt> listReceipts(Long candidateId) {
        return jdbc.query(
                """
                SELECT id, candidate_id, station_code, opening, received_at
                FROM switch_gate_receipt WHERE candidate_id = ? ORDER BY station_code
                """,
                (rs, n) -> new GateReceipt(
                        rs.getLong("id"),
                        rs.getLong("candidate_id"),
                        rs.getString("station_code"),
                        rs.getBigDecimal("opening"),
                        rs.getObject("received_at", LocalDateTime.class)),
                candidateId);
    }

    // ---------- 工况轨迹 ----------

    /** 记录一次成功切换。 */
    public void insertTrajectory(String zoneCode, Long candidateId, String fromMode, String toMode,
                                 int zoneVersion, LocalDateTime tideTime,
                                 LocalDateTime switchTime, LocalDateTime now) {
        jdbc.update(
                """
                INSERT INTO mode_trajectory (zone_code, candidate_id, from_mode, to_mode,
                    zone_version, tide_time, switch_time, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                zoneCode, candidateId, fromMode, toMode, zoneVersion, tideTime, switchTime, now);
    }

    /** 分区全部工况轨迹，按切换时刻排序。 */
    public List<TrajectoryRecord> listTrajectory(String zoneCode) {
        return jdbc.query(
                """
                SELECT id, zone_code, candidate_id, from_mode, to_mode, zone_version,
                    tide_time, switch_time, created_at
                FROM mode_trajectory WHERE zone_code = ? ORDER BY switch_time, id
                """,
                (rs, n) -> new TrajectoryRecord(
                        rs.getLong("id"),
                        rs.getString("zone_code"),
                        rs.getLong("candidate_id"),
                        rs.getString("from_mode"),
                        rs.getString("to_mode"),
                        rs.getInt("zone_version"),
                        rs.getObject("tide_time", LocalDateTime.class),
                        rs.getObject("switch_time", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class)),
                zoneCode);
    }
}
