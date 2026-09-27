package com.example.starter.pump.repo;

import com.example.starter.pump.domain.TideSwitchCandidate;
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
 * 强排切换候选序列与工况轨迹仓储。
 */
@Repository
public class TideSwitchRepository {

    private static final RowMapper<TideSwitchCandidate> MAPPER = (rs, n) -> new TideSwitchCandidate(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("zone_code"),
            rs.getString("target_mode"),
            rs.getLong("tide_reading_id"),
            rs.getBigDecimal("tide_level"),
            rs.getObject("tide_observed_at", LocalDateTime.class),
            rs.getBigDecimal("threshold_snapshot"),
            rs.getInt("base_version"),
            rs.getString("status"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("activated_at", LocalDateTime.class));

    /** 候选站闸门目标（生成时固定快照）。 */
    public record StationTarget(String stationCode, BigDecimal targetOpening) {
    }

    /** 工况轨迹条目（查询视图）。 */
    public record TrailItem(Long id, String zoneCode, String fromMode, String toMode,
                            int version, Long candidateId, String requestId,
                            LocalDateTime switchedAt) {
    }

    private final JdbcTemplate jdbc;

    public TideSwitchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入候选序列并回填自增ID。 */
    public Long insertCandidate(String requestId, String zoneCode, String targetMode,
                                Long tideReadingId, BigDecimal tideLevel,
                                LocalDateTime tideObservedAt, BigDecimal thresholdSnapshot,
                                int baseVersion, LocalDateTime createdAt) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    """
                    INSERT INTO tide_switch_candidate (request_id, zone_code, target_mode,
                        tide_reading_id, tide_level, tide_observed_at, threshold_snapshot,
                        base_version, status, created_at, activated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, NULL)
                    """,
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setString(2, zoneCode);
            ps.setString(3, targetMode);
            ps.setLong(4, tideReadingId);
            ps.setBigDecimal(5, tideLevel);
            ps.setObject(6, tideObservedAt);
            ps.setBigDecimal(7, thresholdSnapshot);
            ps.setInt(8, baseVersion);
            ps.setObject(9, createdAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("候选序列自增ID回填失败");
        }
        return key.longValue();
    }

    /** 固定候选序列中某站的闸门目标开度。 */
    public void insertCandidateStation(Long candidateId, String stationCode,
                                       BigDecimal targetOpening) {
        jdbc.update(
                """
                INSERT INTO tide_switch_candidate_station (candidate_id, station_code,
                    target_opening) VALUES (?, ?, ?)
                """,
                candidateId, stationCode, targetOpening);
    }

    /** 按ID查询候选，不存在返回空。 */
    public Optional<TideSwitchCandidate> findCandidate(Long candidateId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT id, request_id, zone_code, target_mode, tide_reading_id, tide_level,
                        tide_observed_at, threshold_snapshot, base_version, status, created_at,
                        activated_at
                    FROM tide_switch_candidate WHERE id = ?
                    """,
                    MAPPER, candidateId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 分区当前待确认候选（同一分区至多一条 PENDING，由配置行锁保证）。 */
    public Optional<TideSwitchCandidate> findPendingByZone(String zoneCode) {
        List<TideSwitchCandidate> list = jdbc.query(
                """
                SELECT id, request_id, zone_code, target_mode, tide_reading_id, tide_level,
                    tide_observed_at, threshold_snapshot, base_version, status, created_at,
                    activated_at
                FROM tide_switch_candidate
                WHERE zone_code = ? AND status = 'PENDING'
                ORDER BY id DESC LIMIT 1
                """,
                MAPPER, zoneCode);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 更新候选状态；activatedAt 仅启用时非空。 */
    public void updateStatus(Long candidateId, String status, LocalDateTime activatedAt) {
        jdbc.update(
                "UPDATE tide_switch_candidate SET status = ?, activated_at = ? WHERE id = ?",
                status, activatedAt, candidateId);
    }

    /** 候选序列的全部站闸门目标，按站代码排序。 */
    public List<StationTarget> loadCandidateStations(Long candidateId) {
        return jdbc.query(
                """
                SELECT station_code, target_opening FROM tide_switch_candidate_station
                WHERE candidate_id = ? ORDER BY station_code
                """,
                (rs, n) -> new StationTarget(rs.getString("station_code"),
                        rs.getBigDecimal("target_opening")),
                candidateId);
    }

    /** 分区全部候选序列，按ID排序。 */
    public List<TideSwitchCandidate> loadCandidatesByZone(String zoneCode) {
        return jdbc.query(
                """
                SELECT id, request_id, zone_code, target_mode, tide_reading_id, tide_level,
                    tide_observed_at, threshold_snapshot, base_version, status, created_at,
                    activated_at
                FROM tide_switch_candidate WHERE zone_code = ? ORDER BY id
                """,
                MAPPER, zoneCode);
    }

    /** 追加工况轨迹。 */
    public void insertTrail(String zoneCode, String fromMode, String toMode, int version,
                            Long candidateId, String requestId, LocalDateTime switchedAt) {
        jdbc.update(
                """
                INSERT INTO tide_mode_trail (zone_code, from_mode, to_mode, version, candidate_id,
                    request_id, switched_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                zoneCode, fromMode, toMode, version, candidateId, requestId, switchedAt);
    }

    /** 分区工况轨迹，按ID排序。 */
    public List<TrailItem> loadTrail(String zoneCode) {
        return jdbc.query(
                """
                SELECT id, zone_code, from_mode, to_mode, version, candidate_id, request_id,
                    switched_at
                FROM tide_mode_trail WHERE zone_code = ? ORDER BY id
                """,
                (rs, n) -> new TrailItem(
                        rs.getLong("id"),
                        rs.getString("zone_code"),
                        rs.getString("from_mode"),
                        rs.getString("to_mode"),
                        rs.getInt("version"),
                        rs.getLong("candidate_id"),
                        rs.getString("request_id"),
                        rs.getObject("switched_at", LocalDateTime.class)),
                zoneCode);
    }
}
