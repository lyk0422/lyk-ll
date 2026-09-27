package com.example.starter.cems;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 雨季溢流域数据访问层（B 题新增，UTC 墙钟时间）。
 * 全部 SQL 参数化；唯一约束承担“许可版本唯一、小时去重、日结快照唯一、异常段去重”等并发边界。
 */
@Repository
public class OverflowRepository {

    private final JdbcTemplate jdbc;

    public OverflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 溢流许可版本 ----------------

    /** 新建一个许可版本；(排口,版本号) 唯一，版本号由服务层按序分配。 */
    public long insertPermit(String code, int version, LocalDateTime windowStart, LocalDateTime windowEnd,
                             BigDecimal minRainfall, int maxConsecutiveHours, String status,
                             String requestId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO overflow_permit_version(outlet_code, version, window_start_utc, window_end_utc, "
                            + "min_hourly_rainfall, max_consecutive_hours, status, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setInt(2, version);
            ps.setTimestamp(3, Timestamp.valueOf(windowStart));
            ps.setTimestamp(4, Timestamp.valueOf(windowEnd));
            ps.setBigDecimal(5, minRainfall);
            ps.setInt(6, maxConsecutiveHours);
            ps.setString(7, status);
            if (requestId == null) {
                ps.setNull(8, Types.VARCHAR);
            } else {
                ps.setString(8, requestId);
            }
            ps.setTimestamp(9, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Map<String, Object> findPermit(String code, int version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_permit_version WHERE outlet_code = ? AND version = ?", code, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 当前有效版本（status=ACTIVE）；同一排口至多一个 ACTIVE。 */
    public Map<String, Object> findActivePermit(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_permit_version WHERE outlet_code = ? AND status = 'ACTIVE' "
                        + "ORDER BY version DESC", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findPermits(String code) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_permit_version WHERE outlet_code = ? ORDER BY version", code);
    }

    public Integer maxPermitVersion(String code) {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM overflow_permit_version WHERE outlet_code = ?",
                Integer.class, code);
        return v == null ? 0 : v;
    }

    /**
     * 乐观锁置旧：仅当指定版本仍为 ACTIVE 时置 SUPERSEDED。
     * @return 受影响行数；0 表示 expectedVersion 不是当前有效版本（并发已改），调用方返回 409。
     */
    public int supersedePermit(String code, int expectedVersion) {
        return jdbc.update(
                "UPDATE overflow_permit_version SET status = 'SUPERSEDED' "
                        + "WHERE outlet_code = ? AND version = ? AND status = 'ACTIVE'",
                code, expectedVersion);
    }

    // ---------------- 小时记录 ----------------

    /**
     * 写入 UTC 整点小时记录；(排口,小时) 唯一，重复上报由调用方按内容判重放/冲突。
     * @return true 新写入；false 该小时已存在
     */
    public boolean insertHour(String code, LocalDateTime hour, BigDecimal dischargeValue,
                              BigDecimal rainfallMm, int rainfallVersion, boolean observedOverflow,
                              String overflowStatus, Integer evaluatedPermitVersion, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO overflow_hour_record(outlet_code, hour_utc, discharge_value, rainfall_mm, "
                            + "rainfall_version, overflow_observed, overflow_status, evaluated_permit_version, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    code, Timestamp.valueOf(hour), dischargeValue, rainfallMm, rainfallVersion,
                    observedOverflow, overflowStatus,
                    evaluatedPermitVersion == null ? null : evaluatedPermitVersion,
                    Timestamp.valueOf(now), Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Map<String, Object> findHour(String code, LocalDateTime hour) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_hour_record WHERE outlet_code = ? AND hour_utc = ?",
                code, Timestamp.valueOf(hour));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 半开窗口内到点小时 [windowStart, windowEnd)，按小时升序。 */
    public List<Map<String, Object>> findHoursInWindow(String code, LocalDateTime windowStart,
                                                       LocalDateTime windowEnd) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_hour_record WHERE outlet_code = ? AND hour_utc >= ? AND hour_utc < ? "
                        + "ORDER BY hour_utc",
                code, Timestamp.valueOf(windowStart), Timestamp.valueOf(windowEnd));
    }

    /** 该排口全部到点小时（连续段重算用），按小时升序。 */
    public List<Map<String, Object>> findAllHours(String code) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_hour_record WHERE outlet_code = ? ORDER BY hour_utc", code);
    }

    /** 复核冻结前更正雨量：雨量版本 +1，并重写溢流标记（同事务）。 */
    public int updateRainfallAndStatus(String code, LocalDateTime hour, BigDecimal rainfallMm,
                                       int rainfallVersion, String overflowStatus,
                                       Integer evaluatedPermitVersion, LocalDateTime now) {
        return jdbc.update("UPDATE overflow_hour_record SET rainfall_mm = ?, rainfall_version = ?, "
                        + "overflow_status = ?, evaluated_permit_version = ?, updated_at = ? "
                        + "WHERE outlet_code = ? AND hour_utc = ? AND frozen_at IS NULL",
                rainfallMm, rainfallVersion, overflowStatus,
                evaluatedPermitVersion == null ? null : evaluatedPermitVersion,
                Timestamp.valueOf(now), code, Timestamp.valueOf(hour));
    }

    /**
     * 复核冻结：仅当尚未冻结（frozen_at IS NULL）时写入冻结的许可版本与雨量版本。
     * @return 1 冻结成功；0 已被并发冻结（竞争冻结须一致，调用方转读既有冻结结果）
     */
    public int freezeHour(String code, LocalDateTime hour, int permitVersionFrozen, int rainfallVersionFrozen,
                          String requestId, LocalDateTime frozenAt) {
        return jdbc.update("UPDATE overflow_hour_record SET permit_version_frozen = ?, "
                        + "rainfall_version_frozen = ?, review_request_id = ?, frozen_at = ?, updated_at = ? "
                        + "WHERE outlet_code = ? AND hour_utc = ? AND frozen_at IS NULL",
                permitVersionFrozen, rainfallVersionFrozen, requestId, Timestamp.valueOf(frozenAt),
                Timestamp.valueOf(frozenAt), code, Timestamp.valueOf(hour));
    }

    // ---------------- 专项异常 ----------------

    public long insertAnomaly(String code, String anomalyType, LocalDateTime segmentStart,
                              LocalDateTime segmentEnd, int segmentHours, int permitVersion,
                              LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO overflow_anomaly(outlet_code, anomaly_type, segment_start_utc, segment_end_utc, "
                            + "segment_hours, permit_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, anomalyType);
            ps.setTimestamp(3, Timestamp.valueOf(segmentStart));
            ps.setTimestamp(4, Timestamp.valueOf(segmentEnd));
            ps.setInt(5, segmentHours);
            ps.setInt(6, permitVersion);
            ps.setTimestamp(7, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<Map<String, Object>> findAnomalies(String code) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_anomaly WHERE outlet_code = ? ORDER BY segment_start_utc", code);
    }

    /** 重算连续段前清空旧专项异常（幂等复算）。 */
    public int deleteSegmentAnomalies(String code) {
        return jdbc.update("DELETE FROM overflow_anomaly WHERE outlet_code = ? AND anomaly_type = 'SEGMENT_TOO_LONG'",
                code);
    }

    // ---------------- 冻结后独立雨量调差 ----------------

    public void insertAdjustment(String code, LocalDateTime hour, int frozenRainfallVersion,
                                 BigDecimal oldRainfall, BigDecimal newRainfall, String reason,
                                 String requestId, LocalDateTime now) {
        jdbc.update("INSERT INTO rainfall_adjustment(outlet_code, hour_utc, frozen_rainfall_version, "
                        + "old_rainfall_mm, new_rainfall_mm, reason, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                code, Timestamp.valueOf(hour), frozenRainfallVersion, oldRainfall, newRainfall, reason,
                requestId, Timestamp.valueOf(now));
    }

    // ---------------- 日结快照 ----------------

    /** 写入日结快照；同日唯一，重复返回 false 由调用方重放既有快照。 */
    public boolean insertSettlement(String code, LocalDate dayUtc, BigDecimal normalAvg, BigDecimal allAvg,
                                    int normalDenominator, int allDenominator, int requestedHours,
                                    int presentCount, int excludedCount, int pendingCount,
                                    int missingPointCount, LocalDateTime settledAt, String requestId,
                                    LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO overflow_daily_settlement(outlet_code, day_utc, normal_avg, all_avg, "
                            + "normal_denominator, all_denominator, requested_hours, present_count, "
                            + "excluded_count, pending_count, missing_point_count, settled_at, request_id, "
                            + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    code, Date.valueOf(dayUtc), normalAvg, allAvg, normalDenominator, allDenominator,
                    requestedHours, presentCount, excludedCount, pendingCount, missingPointCount,
                    Timestamp.valueOf(settledAt), requestId, Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Map<String, Object> findSettlement(String code, LocalDate dayUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_daily_settlement WHERE outlet_code = ? AND day_utc = ?",
                code, Date.valueOf(dayUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
