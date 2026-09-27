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

    /** 雨量小时数据行（含更正历史，当前值取每小时最大版本）。 */
    public record RainfallRow(long id, long zoneId, LocalDateTime hourStart, double rainfallMm,
                              int version, LocalDateTime correctedAt) {
    }

    /** 阈值策略版本行。 */
    public record PolicyRow(long id, long zoneId, int version, double yellowHourlyRate,
                            double redHourlyRate, double rain24hThreshold, double reductionFactor,
                            LocalDateTime effectiveAt, LocalDateTime submittedAt) {
    }

    /** 复工安全门禁冻结证据行。 */
    public record EvidenceRow(long id, long assessmentId, long chainId, long zoneId, String phase,
                              LocalDateTime windowStart, LocalDateTime windowEnd, String result,
                              String detail, LocalDateTime createdAt) {
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
            rs.getDouble("rainfall_mm"), rs.getInt("version"),
            rs.getTimestamp("corrected_at").toLocalDateTime());

    private static final RowMapper<PolicyRow> POLICY_MAPPER = (rs, rowNum) -> new PolicyRow(
            rs.getLong("id"), rs.getLong("zone_id"), rs.getInt("version"),
            rs.getDouble("yellow_hourly_rate"), rs.getDouble("red_hourly_rate"),
            rs.getDouble("rain_24h_threshold"), rs.getDouble("reduction_factor"),
            rs.getTimestamp("effective_at").toLocalDateTime(),
            rs.getTimestamp("submitted_at").toLocalDateTime());

    private static final RowMapper<EvidenceRow> EVIDENCE_MAPPER = (rs, rowNum) -> new EvidenceRow(
            rs.getLong("id"), rs.getLong("assessment_id"), rs.getLong("chain_id"),
            rs.getLong("zone_id"), rs.getString("phase"),
            rs.getTimestamp("window_start").toLocalDateTime(),
            rs.getTimestamp("window_end").toLocalDateTime(),
            rs.getString("result"), rs.getString("detail"),
            rs.getTimestamp("created_at").toLocalDateTime());

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
    // 雨量小时数据
    // ------------------------------------------------------------------

    public long insertRainfall(long zoneId, LocalDateTime hourStart, double rainfallMm,
                               int version, LocalDateTime correctedAt) {
        return insertAndGetKey(
                "INSERT INTO rainfall_hourly (zone_id, hour_start, rainfall_mm, version, corrected_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, zoneId);
                    ps.setTimestamp(2, Timestamp.valueOf(hourStart));
                    ps.setDouble(3, rainfallMm);
                    ps.setInt(4, version);
                    ps.setTimestamp(5, Timestamp.valueOf(correctedAt));
                });
    }

    /** 某测区某小时的当前雨量（最大版本）。 */
    public Optional<RainfallRow> findLatestRainfall(long zoneId, LocalDateTime hourStart) {
        return jdbcTemplate.query(
                        "SELECT * FROM rainfall_hourly WHERE zone_id = ? AND hour_start = ? "
                                + "ORDER BY version DESC LIMIT 1",
                        RAINFALL_MAPPER, zoneId, Timestamp.valueOf(hourStart))
                .stream().findFirst();
    }

    /** 某测区 [from, to) 内每小时的当前雨量（每小时取最大版本），按小时升序。 */
    public List<RainfallRow> findLatestRainfallInRange(long zoneId, LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query(
                "SELECT * FROM rainfall_hourly r WHERE r.zone_id = ? "
                        + "AND r.hour_start >= ? AND r.hour_start < ? "
                        + "AND r.version = (SELECT MAX(x.version) FROM rainfall_hourly x "
                        + "WHERE x.zone_id = r.zone_id AND x.hour_start = r.hour_start) "
                        + "ORDER BY r.hour_start",
                RAINFALL_MAPPER, zoneId, Timestamp.valueOf(from), Timestamp.valueOf(to));
    }

    /** 某测区全部小时的当前雨量，按小时升序。 */
    public List<RainfallRow> findLatestRainfallByZone(long zoneId) {
        return jdbcTemplate.query(
                "SELECT * FROM rainfall_hourly r WHERE r.zone_id = ? "
                        + "AND r.version = (SELECT MAX(x.version) FROM rainfall_hourly x "
                        + "WHERE x.zone_id = r.zone_id AND x.hour_start = r.hour_start) "
                        + "ORDER BY r.hour_start",
                RAINFALL_MAPPER, zoneId);
    }

    /** 更正历史（含全部版本）；hourStart 为 null 时返回该测区全部历史。 */
    public List<RainfallRow> findRainfallHistory(long zoneId, LocalDateTime hourStart) {
        if (hourStart == null) {
            return jdbcTemplate.query(
                    "SELECT * FROM rainfall_hourly WHERE zone_id = ? ORDER BY hour_start, version",
                    RAINFALL_MAPPER, zoneId);
        }
        return jdbcTemplate.query(
                "SELECT * FROM rainfall_hourly WHERE zone_id = ? AND hour_start = ? ORDER BY version",
                RAINFALL_MAPPER, zoneId, Timestamp.valueOf(hourStart));
    }

    // ------------------------------------------------------------------
    // 阈值策略版本
    // ------------------------------------------------------------------

    public long insertPolicy(long zoneId, int version, double yellowHourlyRate, double redHourlyRate,
                             double rain24hThreshold, double reductionFactor,
                             LocalDateTime effectiveAt, LocalDateTime submittedAt) {
        return insertAndGetKey(
                "INSERT INTO threshold_policy (zone_id, version, yellow_hourly_rate, red_hourly_rate, "
                        + "rain_24h_threshold, reduction_factor, effective_at, submitted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, zoneId);
                    ps.setInt(2, version);
                    ps.setDouble(3, yellowHourlyRate);
                    ps.setDouble(4, redHourlyRate);
                    ps.setDouble(5, rain24hThreshold);
                    ps.setDouble(6, reductionFactor);
                    ps.setTimestamp(7, Timestamp.valueOf(effectiveAt));
                    ps.setTimestamp(8, Timestamp.valueOf(submittedAt));
                });
    }

    /** 当前最新策略版本（版本号最大）。 */
    public Optional<PolicyRow> findLatestPolicyByZone(long zoneId) {
        return jdbcTemplate.query(
                        "SELECT * FROM threshold_policy WHERE zone_id = ? ORDER BY version DESC LIMIT 1",
                        POLICY_MAPPER, zoneId)
                .stream().findFirst();
    }

    /** 全部策略版本，按版本号升序。 */
    public List<PolicyRow> findPoliciesByZone(long zoneId) {
        return jdbcTemplate.query("SELECT * FROM threshold_policy WHERE zone_id = ? ORDER BY version",
                POLICY_MAPPER, zoneId);
    }

    // ------------------------------------------------------------------
    // 复工安全门禁冻结证据
    // ------------------------------------------------------------------

    public long insertEvidence(long assessmentId, long chainId, long zoneId, String phase,
                               LocalDateTime windowStart, LocalDateTime windowEnd,
                               String result, String detail) {
        return insertAndGetKey(
                "INSERT INTO resumption_evidence (assessment_id, chain_id, zone_id, phase, "
                        + "window_start, window_end, result, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, assessmentId);
                    ps.setLong(2, chainId);
                    ps.setLong(3, zoneId);
                    ps.setString(4, phase);
                    ps.setTimestamp(5, Timestamp.valueOf(windowStart));
                    ps.setTimestamp(6, Timestamp.valueOf(windowEnd));
                    ps.setString(7, result);
                    ps.setString(8, detail);
                });
    }

    public List<EvidenceRow> findEvidenceByChain(long chainId) {
        return jdbcTemplate.query("SELECT * FROM resumption_evidence WHERE chain_id = ? ORDER BY id",
                EVIDENCE_MAPPER, chainId);
    }
}
