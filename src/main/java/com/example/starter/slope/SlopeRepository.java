package com.example.starter.slope;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 边坡监测数据访问层：全部使用参数化 SQL。
 * 时间字段为 Asia/Shanghai 本地时间；速率字段单位见 schema.sql 注释。
 */
@Repository
public class SlopeRepository {

    /** 测区行。 */
    public record ZoneRow(long id, String code, String name, double warnHourlyRate,
                          double alarmDailyRate, double safetyHourlyRate, int personnelTotal,
                          String status) {
    }

    /** 雷达读数行。 */
    public record ReadingRow(long id, long zoneId, LocalDateTime measuredAt, double displacementMm,
                             Double hourlyRate, Double dailyRate) {
    }

    /** 处置链行。 */
    public record ChainRow(long id, long zoneId, String level, String status,
                           LocalDateTime triggeredAt, LocalDateTime evacuateDeadline,
                           LocalDateTime evacuatedAt, LocalDateTime archivedAt) {
    }

    /** 撤人确认行。 */
    public record EvacuationRow(long id, long chainId, long zoneId, LocalDateTime confirmedAt,
                                int arrivedCount, boolean onTime) {
    }

    /** 复工评估行。 */
    public record AssessmentRow(long id, long chainId, long zoneId, LocalDateTime appliedAt,
                                boolean siteCheckConfirmed, boolean monitoringReviewConfirmed,
                                String status, LocalDateTime approvedAt) {
    }

    /** 迟到事件行。 */
    public record LateEventRow(long id, long zoneId, Long chainId, LocalDateTime measuredAt,
                               double displacementMm, String reason) {
    }

    /** 合成雨量小时数据行。 */
    public record RainfallRow(long id, long zoneId, LocalDateTime hourStart, double rainfallMm,
                              int version) {
    }

    /** 阈值策略版本行。 */
    public record StrategyRow(long id, long zoneId, int version, double yellowRate, double redRate,
                              double rainfall24hThreshold, double reductionFactor,
                              LocalDateTime effectiveFrom, LocalDateTime submittedAt) {
    }

    /** 复工安全评估冻结证据行。 */
    public record EvidenceRow(long id, long chainId, long assessmentId, long zoneId,
                              LocalDateTime evaluatedAt, LocalDateTime windowStart,
                              LocalDateTime windowEnd, LocalDateTime rainfallWindowStart,
                              Double rainfall24hSum, boolean reductionApplied,
                              Integer strategyVersion, double safetyThreshold, int readingCount,
                              String detail) {
    }

    private static final RowMapper<ZoneRow> ZONE_MAPPER = (rs, rowNum) -> new ZoneRow(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getDouble("warn_hourly_rate"), rs.getDouble("alarm_daily_rate"),
            rs.getDouble("safety_hourly_rate"), rs.getInt("personnel_total"),
            rs.getString("status"));

    private static final RowMapper<ReadingRow> READING_MAPPER = (rs, rowNum) -> new ReadingRow(
            rs.getLong("id"), rs.getLong("zone_id"),
            rs.getTimestamp("measured_at").toLocalDateTime(),
            rs.getDouble("displacement_mm"),
            (Double) rs.getObject("hourly_rate"),
            (Double) rs.getObject("daily_rate"));

    private static final RowMapper<ChainRow> CHAIN_MAPPER = (rs, rowNum) -> new ChainRow(
            rs.getLong("id"), rs.getLong("zone_id"),
            rs.getString("level"), rs.getString("status"),
            rs.getTimestamp("triggered_at").toLocalDateTime(),
            toLocalDateTime(rs.getTimestamp("evacuate_deadline")),
            toLocalDateTime(rs.getTimestamp("evacuated_at")),
            toLocalDateTime(rs.getTimestamp("archived_at")));

    private static final RowMapper<EvacuationRow> EVACUATION_MAPPER = (rs, rowNum) -> new EvacuationRow(
            rs.getLong("id"), rs.getLong("chain_id"), rs.getLong("zone_id"),
            rs.getTimestamp("confirmed_at").toLocalDateTime(),
            rs.getInt("arrived_count"), rs.getBoolean("on_time"));

    private static final RowMapper<AssessmentRow> ASSESSMENT_MAPPER = (rs, rowNum) -> new AssessmentRow(
            rs.getLong("id"), rs.getLong("chain_id"), rs.getLong("zone_id"),
            rs.getTimestamp("applied_at").toLocalDateTime(),
            rs.getBoolean("site_check_confirmed"), rs.getBoolean("monitoring_review_confirmed"),
            rs.getString("status"), toLocalDateTime(rs.getTimestamp("approved_at")));

    private static final RowMapper<LateEventRow> LATE_EVENT_MAPPER = (rs, rowNum) -> new LateEventRow(
            rs.getLong("id"), rs.getLong("zone_id"),
            rs.getObject("chain_id") == null ? null : rs.getLong("chain_id"),
            rs.getTimestamp("measured_at").toLocalDateTime(),
            rs.getDouble("displacement_mm"), rs.getString("reason"));

    private static final RowMapper<RainfallRow> RAINFALL_MAPPER = (rs, rowNum) -> new RainfallRow(
            rs.getLong("id"), rs.getLong("zone_id"),
            rs.getTimestamp("hour_start").toLocalDateTime(),
            rs.getDouble("rainfall_mm"), rs.getInt("version"));

    private static final RowMapper<StrategyRow> STRATEGY_MAPPER = (rs, rowNum) -> new StrategyRow(
            rs.getLong("id"), rs.getLong("zone_id"), rs.getInt("version"),
            rs.getDouble("yellow_rate"), rs.getDouble("red_rate"),
            rs.getDouble("rainfall_24h_threshold"), rs.getDouble("reduction_factor"),
            rs.getTimestamp("effective_from").toLocalDateTime(),
            rs.getTimestamp("submitted_at").toLocalDateTime());

    private static final RowMapper<EvidenceRow> EVIDENCE_MAPPER = (rs, rowNum) -> new EvidenceRow(
            rs.getLong("id"), rs.getLong("chain_id"), rs.getLong("assessment_id"),
            rs.getLong("zone_id"), rs.getTimestamp("evaluated_at").toLocalDateTime(),
            rs.getTimestamp("window_start").toLocalDateTime(),
            rs.getTimestamp("window_end").toLocalDateTime(),
            toLocalDateTime(rs.getTimestamp("rainfall_window_start")),
            (Double) rs.getObject("rainfall_24h_sum"),
            rs.getBoolean("reduction_applied"),
            rs.getObject("strategy_version") == null ? null : rs.getInt("strategy_version"),
            rs.getDouble("safety_threshold"), rs.getInt("reading_count"),
            rs.getString("detail"));

    private final JdbcTemplate jdbcTemplate;

    public SlopeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private long insertAndGetKey(String sql, KeySetter setter) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            setter.set(ps);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey(), "未返回自增主键").longValue();
    }

    @FunctionalInterface
    private interface KeySetter {
        void set(PreparedStatement ps) throws java.sql.SQLException;
    }

    public long insertZone(String code, String name, double warnHourlyRate, double alarmDailyRate,
                           double safetyHourlyRate, int personnelTotal) {
        return insertAndGetKey(
                "INSERT INTO slope_zone (code, name, warn_hourly_rate, alarm_daily_rate, safety_hourly_rate, "
                        + "personnel_total, status) VALUES (?, ?, ?, ?, ?, ?, 'NORMAL')",
                ps -> {
                    ps.setString(1, code);
                    ps.setString(2, name);
                    ps.setDouble(3, warnHourlyRate);
                    ps.setDouble(4, alarmDailyRate);
                    ps.setDouble(5, safetyHourlyRate);
                    ps.setInt(6, personnelTotal);
                });
    }

    public Optional<ZoneRow> findZoneById(long id) {
        return jdbcTemplate.query("SELECT * FROM slope_zone WHERE id = ?", ZONE_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ZoneRow> findZoneByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT * FROM slope_zone WHERE id = ? FOR UPDATE", ZONE_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ZoneRow> findZoneByCode(String code) {
        return jdbcTemplate.query("SELECT * FROM slope_zone WHERE code = ?", ZONE_MAPPER, code)
                .stream().findFirst();
    }

    public void updateZoneStatus(long zoneId, String status) {
        jdbcTemplate.update("UPDATE slope_zone SET status = ? WHERE id = ?", status, zoneId);
    }

    public long insertReading(long zoneId, LocalDateTime measuredAt, double displacementMm) {
        return insertAndGetKey(
                "INSERT INTO radar_reading (zone_id, measured_at, displacement_mm) VALUES (?, ?, ?)",
                ps -> {
                    ps.setLong(1, zoneId);
                    ps.setTimestamp(2, Timestamp.valueOf(measuredAt));
                    ps.setDouble(3, displacementMm);
                });
    }

    public Optional<ReadingRow> findReading(long zoneId, LocalDateTime measuredAt) {
        return jdbcTemplate.query("SELECT * FROM radar_reading WHERE zone_id = ? AND measured_at = ?",
                        READING_MAPPER, zoneId, Timestamp.valueOf(measuredAt))
                .stream().findFirst();
    }

    public List<ReadingRow> findReadingsByZone(long zoneId) {
        return jdbcTemplate.query("SELECT * FROM radar_reading WHERE zone_id = ? ORDER BY measured_at",
                READING_MAPPER, zoneId);
    }

    public void updateReadingRates(long readingId, Double hourlyRate, Double dailyRate) {
        jdbcTemplate.update("UPDATE radar_reading SET hourly_rate = ?, daily_rate = ? WHERE id = ?",
                hourlyRate, dailyRate, readingId);
    }

    public long insertChain(long zoneId, String level, String status, LocalDateTime triggeredAt,
                            LocalDateTime evacuateDeadline) {
        return insertAndGetKey(
                "INSERT INTO disposal_chain (zone_id, level, status, triggered_at, evacuate_deadline) "
                        + "VALUES (?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, zoneId);
                    ps.setString(2, level);
                    ps.setString(3, status);
                    ps.setTimestamp(4, Timestamp.valueOf(triggeredAt));
                    if (evacuateDeadline == null) {
                        ps.setTimestamp(5, null);
                    } else {
                        ps.setTimestamp(5, Timestamp.valueOf(evacuateDeadline));
                    }
                });
    }

    public Optional<ChainRow> findChainById(long chainId) {
        return jdbcTemplate.query("SELECT * FROM disposal_chain WHERE id = ?", CHAIN_MAPPER, chainId)
                .stream().findFirst();
    }

    public Optional<ChainRow> findChainByIdForUpdate(long chainId) {
        return jdbcTemplate.query("SELECT * FROM disposal_chain WHERE id = ? FOR UPDATE", CHAIN_MAPPER, chainId)
                .stream().findFirst();
    }

    public Optional<ChainRow> findExecutingChainByZone(long zoneId) {
        return jdbcTemplate.query(
                        "SELECT * FROM disposal_chain WHERE zone_id = ? AND status = 'EXECUTING' "
                                + "ORDER BY id DESC LIMIT 1",
                        CHAIN_MAPPER, zoneId)
                .stream().findFirst();
    }

    public Optional<ChainRow> findLatestArchivedChainByZone(long zoneId) {
        return jdbcTemplate.query(
                        "SELECT * FROM disposal_chain WHERE zone_id = ? AND status = 'ARCHIVED' "
                                + "ORDER BY archived_at DESC, id DESC LIMIT 1",
                        CHAIN_MAPPER, zoneId)
                .stream().findFirst();
    }

    public void upgradeChainToRed(long chainId, LocalDateTime triggeredAt, LocalDateTime evacuateDeadline) {
        jdbcTemplate.update(
                "UPDATE disposal_chain SET level = 'RED', triggered_at = ?, evacuate_deadline = ? WHERE id = ?",
                Timestamp.valueOf(triggeredAt), Timestamp.valueOf(evacuateDeadline), chainId);
    }

    public void markChainEvacuated(long chainId, LocalDateTime evacuatedAt) {
        jdbcTemplate.update("UPDATE disposal_chain SET evacuated_at = ? WHERE id = ?",
                Timestamp.valueOf(evacuatedAt), chainId);
    }

    public void updateChainStatus(long chainId, String status, LocalDateTime archivedAt) {
        if (archivedAt == null) {
            jdbcTemplate.update("UPDATE disposal_chain SET status = ? WHERE id = ?", status, chainId);
        } else {
            jdbcTemplate.update("UPDATE disposal_chain SET status = ?, archived_at = ? WHERE id = ?",
                    status, Timestamp.valueOf(archivedAt), chainId);
        }
    }

    public long insertEvacuation(long chainId, long zoneId, LocalDateTime confirmedAt,
                                 int arrivedCount, boolean onTime) {
        return insertAndGetKey(
                "INSERT INTO evacuation_confirmation (chain_id, zone_id, confirmed_at, arrived_count, on_time) "
                        + "VALUES (?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, chainId);
                    ps.setLong(2, zoneId);
                    ps.setTimestamp(3, Timestamp.valueOf(confirmedAt));
                    ps.setInt(4, arrivedCount);
                    ps.setBoolean(5, onTime);
                });
    }

    public Optional<EvacuationRow> findEvacuationByChain(long chainId) {
        return jdbcTemplate.query("SELECT * FROM evacuation_confirmation WHERE chain_id = ?",
                        EVACUATION_MAPPER, chainId)
                .stream().findFirst();
    }

    public long insertAssessment(long chainId, long zoneId, LocalDateTime appliedAt) {
        return insertAndGetKey(
                "INSERT INTO resumption_assessment (chain_id, zone_id, applied_at, status) VALUES (?, ?, ?, 'APPLIED')",
                ps -> {
                    ps.setLong(1, chainId);
                    ps.setLong(2, zoneId);
                    ps.setTimestamp(3, Timestamp.valueOf(appliedAt));
                });
    }

    public Optional<AssessmentRow> findAssessmentByChain(long chainId) {
        return jdbcTemplate.query("SELECT * FROM resumption_assessment WHERE chain_id = ?",
                        ASSESSMENT_MAPPER, chainId)
                .stream().findFirst();
    }

    public Optional<AssessmentRow> findAssessmentByChainForUpdate(long chainId) {
        return jdbcTemplate.query("SELECT * FROM resumption_assessment WHERE chain_id = ? FOR UPDATE",
                        ASSESSMENT_MAPPER, chainId)
                .stream().findFirst();
    }

    public void updateAssessmentConfirmation(long assessmentId, boolean siteCheck,
                                             boolean monitoringReview, String status,
                                             LocalDateTime approvedAt) {
        jdbcTemplate.update(
                "UPDATE resumption_assessment SET site_check_confirmed = ?, monitoring_review_confirmed = ?, "
                        + "status = ?, approved_at = ? WHERE id = ?",
                siteCheck, monitoringReview, status,
                approvedAt == null ? null : Timestamp.valueOf(approvedAt), assessmentId);
    }

    public long insertLateEvent(long zoneId, Long chainId, LocalDateTime measuredAt,
                                double displacementMm, String reason) {
        return insertAndGetKey(
                "INSERT INTO late_event (zone_id, chain_id, measured_at, displacement_mm, reason) "
                        + "VALUES (?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, zoneId);
                    if (chainId == null) {
                        ps.setObject(2, null);
                    } else {
                        ps.setLong(2, chainId);
                    }
                    ps.setTimestamp(3, Timestamp.valueOf(measuredAt));
                    ps.setDouble(4, displacementMm);
                    ps.setString(5, reason);
                });
    }

    public List<LateEventRow> findLateEventsByZone(long zoneId) {
        return jdbcTemplate.query("SELECT * FROM late_event WHERE zone_id = ? ORDER BY id",
                LATE_EVENT_MAPPER, zoneId);
    }

    // ------------------------------------------------------------------
    // 合成雨量小时数据
    // ------------------------------------------------------------------

    public long insertRainfall(long zoneId, LocalDateTime hourStart, double rainfallMm) {
        return insertAndGetKey(
                "INSERT INTO rainfall_hourly (zone_id, hour_start, rainfall_mm, version) "
                        + "VALUES (?, ?, ?, 1)",
                ps -> {
                    ps.setLong(1, zoneId);
                    ps.setTimestamp(2, Timestamp.valueOf(hourStart));
                    ps.setDouble(3, rainfallMm);
                });
    }

    public Optional<RainfallRow> findRainfall(long zoneId, LocalDateTime hourStart) {
        return jdbcTemplate.query("SELECT * FROM rainfall_hourly WHERE zone_id = ? AND hour_start = ?",
                        RAINFALL_MAPPER, zoneId, Timestamp.valueOf(hourStart))
                .stream().findFirst();
    }

    public List<RainfallRow> findRainfallByZoneBetween(long zoneId, LocalDateTime fromInclusive,
                                                       LocalDateTime toExclusive) {
        return jdbcTemplate.query(
                "SELECT * FROM rainfall_hourly WHERE zone_id = ? AND hour_start >= ? "
                        + "AND hour_start < ? ORDER BY hour_start",
                RAINFALL_MAPPER, zoneId, Timestamp.valueOf(fromInclusive),
                Timestamp.valueOf(toExclusive));
    }

    public void updateRainfall(long rainfallId, double rainfallMm, int newVersion) {
        jdbcTemplate.update(
                "UPDATE rainfall_hourly SET rainfall_mm = ?, version = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ?",
                rainfallMm, newVersion, rainfallId);
    }

    // ------------------------------------------------------------------
    // 阈值策略版本
    // ------------------------------------------------------------------

    public long insertStrategy(long zoneId, int version, double yellowRate, double redRate,
                               double rainfall24hThreshold, double reductionFactor,
                               LocalDateTime effectiveFrom, LocalDateTime submittedAt) {
        return insertAndGetKey(
                "INSERT INTO threshold_strategy (zone_id, version, yellow_rate, red_rate, "
                        + "rainfall_24h_threshold, reduction_factor, effective_from, submitted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, zoneId);
                    ps.setInt(2, version);
                    ps.setDouble(3, yellowRate);
                    ps.setDouble(4, redRate);
                    ps.setDouble(5, rainfall24hThreshold);
                    ps.setDouble(6, reductionFactor);
                    ps.setTimestamp(7, Timestamp.valueOf(effectiveFrom));
                    ps.setTimestamp(8, Timestamp.valueOf(submittedAt));
                });
    }

    public List<StrategyRow> findStrategiesByZone(long zoneId) {
        return jdbcTemplate.query(
                "SELECT * FROM threshold_strategy WHERE zone_id = ? ORDER BY version",
                STRATEGY_MAPPER, zoneId);
    }

    public Optional<StrategyRow> findLatestStrategyByZone(long zoneId) {
        return jdbcTemplate.query(
                        "SELECT * FROM threshold_strategy WHERE zone_id = ? "
                                + "ORDER BY version DESC LIMIT 1",
                        STRATEGY_MAPPER, zoneId)
                .stream().findFirst();
    }

    /** 指定时刻生效的策略版本：effective_from 不晚于该时刻的最新发布版本。 */
    public Optional<StrategyRow> findStrategyEffectiveAt(long zoneId, LocalDateTime at) {
        return jdbcTemplate.query(
                        "SELECT * FROM threshold_strategy WHERE zone_id = ? AND effective_from <= ? "
                                + "ORDER BY effective_from DESC, version DESC LIMIT 1",
                        STRATEGY_MAPPER, zoneId, Timestamp.valueOf(at))
                .stream().findFirst();
    }

    // ------------------------------------------------------------------
    // 复工安全评估冻结证据
    // ------------------------------------------------------------------

    public long insertEvidence(long chainId, long assessmentId, long zoneId,
                               LocalDateTime evaluatedAt, LocalDateTime windowStart,
                               LocalDateTime windowEnd, LocalDateTime rainfallWindowStart,
                               Double rainfall24hSum, boolean reductionApplied,
                               Integer strategyVersion, double safetyThreshold, int readingCount,
                               String detail) {
        return insertAndGetKey(
                "INSERT INTO resumption_evidence (chain_id, assessment_id, zone_id, evaluated_at, "
                        + "window_start, window_end, rainfall_window_start, rainfall_24h_sum, "
                        + "reduction_applied, strategy_version, safety_threshold, reading_count, detail) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, chainId);
                    ps.setLong(2, assessmentId);
                    ps.setLong(3, zoneId);
                    ps.setTimestamp(4, Timestamp.valueOf(evaluatedAt));
                    ps.setTimestamp(5, Timestamp.valueOf(windowStart));
                    ps.setTimestamp(6, Timestamp.valueOf(windowEnd));
                    if (rainfallWindowStart == null) {
                        ps.setObject(7, null);
                    } else {
                        ps.setTimestamp(7, Timestamp.valueOf(rainfallWindowStart));
                    }
                    if (rainfall24hSum == null) {
                        ps.setObject(8, null);
                    } else {
                        ps.setDouble(8, rainfall24hSum);
                    }
                    ps.setBoolean(9, reductionApplied);
                    if (strategyVersion == null) {
                        ps.setObject(10, null);
                    } else {
                        ps.setInt(10, strategyVersion);
                    }
                    ps.setDouble(11, safetyThreshold);
                    ps.setInt(12, readingCount);
                    ps.setString(13, detail);
                });
    }

    public List<EvidenceRow> findEvidenceByChain(long chainId) {
        return jdbcTemplate.query(
                "SELECT * FROM resumption_evidence WHERE chain_id = ? ORDER BY id",
                EVIDENCE_MAPPER, chainId);
    }
}
