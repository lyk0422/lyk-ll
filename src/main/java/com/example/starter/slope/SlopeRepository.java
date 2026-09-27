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
                                LocalDateTime siteCheckConfirmedAt, LocalDateTime monitoringReviewConfirmedAt,
                                String status, LocalDateTime approvedAt) {
    }

    /** 迟到事件行。 */
    public record LateEventRow(long id, long zoneId, Long chainId, LocalDateTime measuredAt,
                               double displacementMm, String reason) {
    }

    /** 合成小时雨量行（hourUtc 为 UTC 整点墙钟）。 */
    public record RainfallHourRow(long id, long zoneId, LocalDateTime hourUtc, double rainMm, int version) {
    }

    /** 雨量联动阈值策略版本行。 */
    public record PolicyVersionRow(long id, long zoneId, int version, double yellowHourlyRate,
                                   double redDailyRate, double rainGateMm, double reductionFactor,
                                   LocalDateTime submittedAt, LocalDateTime effectiveAt,
                                   boolean tightenedFromPrevious) {
    }

    /** 复工冻结证据行。 */
    public record EvidenceRow(long id, long assessmentId, long chainId, long zoneId,
                              LocalDateTime frozenAt, LocalDateTime windowStart, LocalDateTime windowEnd,
                              LocalDateTime lastTightenedAt, String evidenceJson) {
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
            toLocalDateTime(rs.getTimestamp("site_check_confirmed_at")),
            toLocalDateTime(rs.getTimestamp("monitoring_review_confirmed_at")),
            rs.getString("status"), toLocalDateTime(rs.getTimestamp("approved_at")));

    private static final RowMapper<LateEventRow> LATE_EVENT_MAPPER = (rs, rowNum) -> new LateEventRow(
            rs.getLong("id"), rs.getLong("zone_id"),
            rs.getObject("chain_id") == null ? null : rs.getLong("chain_id"),
            rs.getTimestamp("measured_at").toLocalDateTime(),
            rs.getDouble("displacement_mm"), rs.getString("reason"));

    private static final RowMapper<RainfallHourRow> RAINFALL_MAPPER = (rs, rowNum) -> new RainfallHourRow(
            rs.getLong("id"), rs.getLong("zone_id"),
            rs.getTimestamp("hour_utc").toLocalDateTime(),
            rs.getDouble("rain_mm"), rs.getInt("version"));

    private static final RowMapper<PolicyVersionRow> POLICY_MAPPER = (rs, rowNum) -> new PolicyVersionRow(
            rs.getLong("id"), rs.getLong("zone_id"), rs.getInt("version"),
            rs.getDouble("yellow_hourly_rate"), rs.getDouble("red_daily_rate"),
            rs.getDouble("rain_gate_mm"), rs.getDouble("reduction_factor"),
            rs.getTimestamp("submitted_at").toLocalDateTime(),
            rs.getTimestamp("effective_at").toLocalDateTime(),
            rs.getBoolean("tightened_from_previous"));

    private static final RowMapper<EvidenceRow> EVIDENCE_MAPPER = (rs, rowNum) -> new EvidenceRow(
            rs.getLong("id"), rs.getLong("assessment_id"), rs.getLong("chain_id"),
            rs.getLong("zone_id"), rs.getTimestamp("frozen_at").toLocalDateTime(),
            rs.getTimestamp("window_start").toLocalDateTime(),
            rs.getTimestamp("window_end").toLocalDateTime(),
            toLocalDateTime(rs.getTimestamp("last_tightened_at")),
            rs.getString("evidence_json"));

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
                                             boolean monitoringReview,
                                             LocalDateTime siteCheckConfirmedAt,
                                             LocalDateTime monitoringReviewConfirmedAt,
                                             String status,
                                             LocalDateTime approvedAt) {
        jdbcTemplate.update(
                "UPDATE resumption_assessment SET site_check_confirmed = ?, monitoring_review_confirmed = ?, "
                        + "site_check_confirmed_at = ?, monitoring_review_confirmed_at = ?, "
                        + "status = ?, approved_at = ? WHERE id = ?",
                siteCheck, monitoringReview,
                siteCheckConfirmedAt == null ? null : Timestamp.valueOf(siteCheckConfirmedAt),
                monitoringReviewConfirmedAt == null ? null : Timestamp.valueOf(monitoringReviewConfirmedAt),
                status,
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
    // 合成小时雨量（UTC 整点）
    // ------------------------------------------------------------------

    public long insertRainfallHour(long zoneId, LocalDateTime hourUtc, double rainMm) {
        return insertAndGetKey(
                "INSERT INTO rainfall_hour (zone_id, hour_utc, rain_mm, version) VALUES (?, ?, ?, 1)",
                ps -> {
                    ps.setLong(1, zoneId);
                    ps.setTimestamp(2, Timestamp.valueOf(hourUtc));
                    ps.setDouble(3, rainMm);
                });
    }

    public Optional<RainfallHourRow> findRainfallHour(long zoneId, LocalDateTime hourUtc) {
        return jdbcTemplate.query(
                        "SELECT * FROM rainfall_hour WHERE zone_id = ? AND hour_utc = ?",
                        RAINFALL_MAPPER, zoneId, Timestamp.valueOf(hourUtc))
                .stream().findFirst();
    }

    public Optional<RainfallHourRow> findRainfallHourForUpdate(long zoneId, LocalDateTime hourUtc) {
        return jdbcTemplate.query(
                        "SELECT * FROM rainfall_hour WHERE zone_id = ? AND hour_utc = ? FOR UPDATE",
                        RAINFALL_MAPPER, zoneId, Timestamp.valueOf(hourUtc))
                .stream().findFirst();
    }

    public List<RainfallHourRow> findRainfallHoursByZone(long zoneId) {
        return jdbcTemplate.query("SELECT * FROM rainfall_hour WHERE zone_id = ? ORDER BY hour_utc",
                RAINFALL_MAPPER, zoneId);
    }

    /** 显式更正：异值写入并将版本加1，仅影响更新到的行。 */
    public int correctRainfallHour(long rainfallId, double rainMm, int expectedVersion) {
        return jdbcTemplate.update(
                "UPDATE rainfall_hour SET rain_mm = ?, version = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND version = ?",
                rainMm, expectedVersion + 1, rainfallId, expectedVersion);
    }

    // ------------------------------------------------------------------
    // 雨量联动阈值策略版本
    // ------------------------------------------------------------------

    public long insertPolicyVersion(long zoneId, int version, double yellowHourlyRate,
                                    double redDailyRate, double rainGateMm, double reductionFactor,
                                    LocalDateTime submittedAt, LocalDateTime effectiveAt,
                                    boolean tightenedFromPrevious) {
        return insertAndGetKey(
                "INSERT INTO threshold_policy_version (zone_id, version, yellow_hourly_rate, red_daily_rate, "
                        + "rain_gate_mm, reduction_factor, submitted_at, effective_at, tightened_from_previous) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, zoneId);
                    ps.setInt(2, version);
                    ps.setDouble(3, yellowHourlyRate);
                    ps.setDouble(4, redDailyRate);
                    ps.setDouble(5, rainGateMm);
                    ps.setDouble(6, reductionFactor);
                    ps.setTimestamp(7, Timestamp.valueOf(submittedAt));
                    ps.setTimestamp(8, Timestamp.valueOf(effectiveAt));
                    ps.setBoolean(9, tightenedFromPrevious);
                });
    }

    public Optional<PolicyVersionRow> findLatestPolicyVersionByZone(long zoneId) {
        return jdbcTemplate.query(
                        "SELECT * FROM threshold_policy_version WHERE zone_id = ? "
                                + "ORDER BY version DESC LIMIT 1",
                        POLICY_MAPPER, zoneId)
                .stream().findFirst();
    }

    public Optional<PolicyVersionRow> findLatestPolicyVersionByZoneForUpdate(long zoneId) {
        return jdbcTemplate.query(
                        "SELECT * FROM threshold_policy_version WHERE zone_id = ? "
                                + "ORDER BY version DESC LIMIT 1 FOR UPDATE",
                        POLICY_MAPPER, zoneId)
                .stream().findFirst();
    }

    public List<PolicyVersionRow> findPolicyVersionsByZone(long zoneId) {
        return jdbcTemplate.query(
                "SELECT * FROM threshold_policy_version WHERE zone_id = ? ORDER BY version",
                POLICY_MAPPER, zoneId);
    }

    /** 指定 Asia/Shanghai 本地时刻实际生效的策略版本：生效时刻恰等即生效，取版本号最大者。 */
    public Optional<PolicyVersionRow> findEffectivePolicyAt(long zoneId, LocalDateTime at) {
        return jdbcTemplate.query(
                        "SELECT * FROM threshold_policy_version WHERE zone_id = ? AND effective_at <= ? "
                                + "ORDER BY version DESC LIMIT 1",
                        POLICY_MAPPER, zoneId, Timestamp.valueOf(at))
                .stream().findFirst();
    }

    // ------------------------------------------------------------------
    // 复工冻结证据
    // ------------------------------------------------------------------

    public long insertResumptionEvidence(long assessmentId, long chainId, long zoneId,
                                         LocalDateTime frozenAt, LocalDateTime windowStart,
                                         LocalDateTime windowEnd, LocalDateTime lastTightenedAt,
                                         String evidenceJson) {
        return insertAndGetKey(
                "INSERT INTO resumption_evidence (assessment_id, chain_id, zone_id, frozen_at, "
                        + "window_start, window_end, last_tightened_at, evidence_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, assessmentId);
                    ps.setLong(2, chainId);
                    ps.setLong(3, zoneId);
                    ps.setTimestamp(4, Timestamp.valueOf(frozenAt));
                    ps.setTimestamp(5, Timestamp.valueOf(windowStart));
                    ps.setTimestamp(6, Timestamp.valueOf(windowEnd));
                    if (lastTightenedAt == null) {
                        ps.setTimestamp(7, null);
                    } else {
                        ps.setTimestamp(7, Timestamp.valueOf(lastTightenedAt));
                    }
                    ps.setString(8, evidenceJson);
                });
    }

    public Optional<EvidenceRow> findEvidenceByChain(long chainId) {
        return jdbcTemplate.query("SELECT * FROM resumption_evidence WHERE chain_id = ?",
                        EVIDENCE_MAPPER, chainId)
                .stream().findFirst();
    }

    public Optional<EvidenceRow> findEvidenceByAssessment(long assessmentId) {
        return jdbcTemplate.query("SELECT * FROM resumption_evidence WHERE assessment_id = ?",
                        EVIDENCE_MAPPER, assessmentId)
                .stream().findFirst();
    }
}
