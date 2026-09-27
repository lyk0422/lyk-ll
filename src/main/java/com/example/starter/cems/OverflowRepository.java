package com.example.starter.cems;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 雨季溢流数据访问层：全部 SQL 参数化。
 * 时间列均为“UTC 墙面时间（LocalDateTime）”，与既有 Asia/Shanghai 口径的表互不影响。
 */
@Repository
public class OverflowRepository {

    private final JdbcTemplate jdbc;

    public OverflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 溢流许可版本 ----------------

    /** 新增一个溢流许可版本（旧版本永久保留为证据）。 */
    public void insertPermit(String code, int version, LocalDateTime validFromUtc, LocalDateTime validToUtc,
                             BigDecimal minRainfallMm, int maxConsecutiveHours, LocalDateTime now) {
        jdbc.update("INSERT INTO overflow_permit(outlet_code, version, valid_from_utc, valid_to_utc, "
                        + "min_hourly_rainfall_mm, max_consecutive_overflow_hours, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                code, version, Timestamp.valueOf(validFromUtc), Timestamp.valueOf(validToUtc),
                minRainfallMm, maxConsecutiveHours, Timestamp.valueOf(now));
    }

    public List<Map<String, Object>> findPermits(String code) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_permit WHERE outlet_code = ? ORDER BY version", code);
    }

    public Map<String, Object> findPermit(String code, int version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_permit WHERE outlet_code = ? AND version = ?", code, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findLatestPermit(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_permit WHERE outlet_code = ? ORDER BY version DESC LIMIT 1", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查找与给定半开窗口重叠的任意许可版本（首尾相接不算重叠）。 */
    public List<Map<String, Object>> findOverlappingPermits(String code, LocalDateTime fromUtc,
                                                            LocalDateTime toUtc) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_permit WHERE outlet_code = ? AND valid_from_utc < ? AND ? < valid_to_utc "
                        + "ORDER BY version",
                code, Timestamp.valueOf(toUtc), Timestamp.valueOf(fromUtc));
    }

    /** 查找该 UTC 小时完整落入其半开窗口的许可版本（取版本号最大者）。 */
    public Map<String, Object> findPermitCoveringHour(String code, LocalDateTime hourUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_permit WHERE outlet_code = ? AND valid_from_utc <= ? AND ? < valid_to_utc "
                        + "ORDER BY version DESC LIMIT 1",
                code, Timestamp.valueOf(hourUtc), Timestamp.valueOf(hourUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- 小时雨量版本 ----------------

    /** 新增某小时的一个雨量版本；同小时同版本唯一，并发同版本由约束拒绝。 */
    public boolean insertRainfall(String code, LocalDateTime hourUtc, int version,
                                  BigDecimal rainfallMm, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO rainfall_reading(outlet_code, hour_utc, version, rainfall_mm, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    code, Timestamp.valueOf(hourUtc), version, rainfallMm, Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public List<Map<String, Object>> findRainfallVersions(String code, LocalDateTime hourUtc) {
        return jdbc.queryForList(
                "SELECT * FROM rainfall_reading WHERE outlet_code = ? AND hour_utc = ? ORDER BY version",
                code, Timestamp.valueOf(hourUtc));
    }

    public Map<String, Object> findRainfallAtVersion(String code, LocalDateTime hourUtc, int version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM rainfall_reading WHERE outlet_code = ? AND hour_utc = ? AND version = ?",
                code, Timestamp.valueOf(hourUtc), version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 某小时最新雨量版本；无任何版本行即“缺雨量”（语义不同于雨量 0.0）。 */
    public Map<String, Object> findLatestRainfall(String code, LocalDateTime hourUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM rainfall_reading WHERE outlet_code = ? AND hour_utc = ? ORDER BY version DESC LIMIT 1",
                code, Timestamp.valueOf(hourUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- 溢流小时记录 ----------------

    /**
     * 写入 UTC 整点小时记录；按“排口＋小时”唯一约束去重。
     * @return true 新写入；false 该小时记录已存在
     */
    public boolean insertOverflowHour(String code, LocalDateTime hourUtc, BigDecimal avgValue,
                                      boolean overflowFlag, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO overflow_hourly_record(outlet_code, hour_utc, avg_value, overflow_flag, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    code, Timestamp.valueOf(hourUtc), avgValue, overflowFlag, Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Map<String, Object> findOverflowHour(String code, LocalDateTime hourUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_hourly_record WHERE outlet_code = ? AND hour_utc = ?",
                code, Timestamp.valueOf(hourUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findOverflowHours(String code) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_hourly_record WHERE outlet_code = ? ORDER BY hour_utc", code);
    }

    public List<Map<String, Object>> findOverflowHoursBetween(String code, LocalDateTime fromUtc,
                                                              LocalDateTime toUtcExclusive) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_hourly_record WHERE outlet_code = ? AND hour_utc >= ? AND hour_utc < ? "
                        + "ORDER BY hour_utc",
                code, Timestamp.valueOf(fromUtc), Timestamp.valueOf(toUtcExclusive));
    }

    // ---------------- 溢流证据标记 ----------------

    /** 复核前（重算）写入/覆盖候选标记：删除旧候选行后插入，状态仅为 PENDING/READY/INELIGIBLE。 */
    public void deleteCandidateMark(String code, LocalDateTime hourUtc) {
        jdbc.update("DELETE FROM overflow_mark WHERE outlet_code = ? AND hour_utc = ? "
                + "AND status <> 'FROZEN_COMPLIANT'", code, Timestamp.valueOf(hourUtc));
    }

    public void insertCandidateMark(String code, LocalDateTime hourUtc, String status,
                                    Integer candidatePermitVersion, Integer candidateRainfallVersion,
                                    BigDecimal candidateRainfallMm, LocalDateTime now) {
        jdbc.update("INSERT INTO overflow_mark(outlet_code, hour_utc, status, candidate_permit_version, "
                        + "candidate_rainfall_version, candidate_rainfall_mm, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                code, Timestamp.valueOf(hourUtc), status, candidatePermitVersion,
                candidateRainfallVersion, candidateRainfallMm, Timestamp.valueOf(now));
    }

    public Map<String, Object> findOverflowMark(String code, LocalDateTime hourUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_mark WHERE outlet_code = ? AND hour_utc = ?",
                code, Timestamp.valueOf(hourUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findOverflowMarksBetween(String code, LocalDateTime fromUtc,
                                                              LocalDateTime toUtcExclusive) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_mark WHERE outlet_code = ? AND hour_utc >= ? AND hour_utc < ? ORDER BY hour_utc",
                code, Timestamp.valueOf(fromUtc), Timestamp.valueOf(toUtcExclusive));
    }

    public List<Map<String, Object>> findOverflowMarks(String code) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_mark WHERE outlet_code = ? ORDER BY hour_utc", code);
    }

    /**
     * 复核冻结：仅 READY（证据齐全）小时可冻结；条件更新返回 0 表示状态已变化（竞争失败/缺证）。
     * 冻结后雨量更正只能走独立调差，不改这些 frozen_* 列。
     */
    public int freezeMark(String code, LocalDateTime hourUtc, int permitVersion, int rainfallVersion,
                          BigDecimal rainfallMm, String requestId, LocalDateTime frozenAt) {
        return jdbc.update("UPDATE overflow_mark SET status = 'FROZEN_COMPLIANT', "
                        + "frozen_permit_version = ?, frozen_rainfall_version = ?, frozen_rainfall_mm = ?, "
                        + "request_id = ?, frozen_at = ? WHERE outlet_code = ? AND hour_utc = ? AND status = 'READY'",
                permitVersion, rainfallVersion, rainfallMm, requestId, Timestamp.valueOf(frozenAt),
                code, Timestamp.valueOf(hourUtc));
    }

    // ---------------- 日结冻结与双口径快照 ----------------

    /** 占位冻结行：唯一约束承担标记复核与日结的并发竞争，先提交者冻结该 UTC 日。 */
    public boolean insertDailyFreeze(String code, LocalDateTime dayUtc, String requestId, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO overflow_daily_freeze(outlet_code, day_utc, request_id, frozen_at) "
                            + "VALUES (?, ?, ?, ?)",
                    code, Timestamp.valueOf(dayUtc), requestId, Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Map<String, Object> findDailyFreeze(String code, LocalDateTime dayUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_daily_freeze WHERE outlet_code = ? AND day_utc = ?",
                code, Timestamp.valueOf(dayUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 冻结事务开始时对排口行加排他行锁，串行化同一排口的日结竞争，保证双口径结果一致。 */
    public void lockOutletForFreeze(String code) {
        jdbc.queryForList("SELECT * FROM discharge_outlet WHERE outlet_code = ? FOR UPDATE", code);
    }

    public void insertDailySummary(String code, LocalDateTime dayUtc, String caliber, BigDecimal averageValue,
                                   int denominator, int excludedHours, int missingPoints, LocalDateTime frozenAt) {
        jdbc.update("INSERT INTO overflow_daily_summary(outlet_code, day_utc, caliber, average_value, denominator, "
                        + "excluded_hours, missing_points, frozen_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                code, Timestamp.valueOf(dayUtc), caliber, averageValue, denominator,
                excludedHours, missingPoints, Timestamp.valueOf(frozenAt));
    }

    public List<Map<String, Object>> findDailySummaries(String code, LocalDateTime dayUtc) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_daily_summary WHERE outlet_code = ? AND day_utc = ? ORDER BY caliber",
                code, Timestamp.valueOf(dayUtc));
    }

    // ---------------- 专项异常 ----------------

    /** 冻结事务内仅清掉“起点落在被复核 UTC 日”的异常后按断段规则重算，绝不改动其他日的冻结证据。 */
    public int deleteAnomaliesForDay(String code, LocalDateTime dayStartUtc) {
        return jdbc.update("DELETE FROM overflow_anomaly WHERE outlet_code = ? AND segment_from_utc >= ? "
                        + "AND segment_from_utc < ?",
                code, Timestamp.valueOf(dayStartUtc), Timestamp.valueOf(dayStartUtc.plusDays(1)));
    }

    public boolean insertAnomaly(String code, String anomalyType, LocalDateTime segmentFromUtc,
                                 LocalDateTime segmentToUtc, int consecutiveHours, int limitHours,
                                 Integer permitVersion, String detail, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO overflow_anomaly(outlet_code, anomaly_type, segment_from_utc, segment_to_utc, "
                            + "consecutive_hours, limit_hours, permit_version, detail, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    code, anomalyType, Timestamp.valueOf(segmentFromUtc), Timestamp.valueOf(segmentToUtc),
                    consecutiveHours, limitHours, permitVersion, detail, Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public List<Map<String, Object>> findAnomalies(String code) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_anomaly WHERE outlet_code = ? ORDER BY segment_from_utc", code);
    }

    // ---------------- 冻结后独立雨量调差 ----------------

    public void insertAdjustment(String code, LocalDateTime hourUtc, Integer frozenRainfallVersion,
                                 int newRainfallVersion, BigDecimal frozenRainfallMm, BigDecimal newRainfallMm,
                                 String reason, LocalDateTime now) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rainfall_adjustment(outlet_code, hour_utc, frozen_rainfall_version, "
                            + "new_rainfall_version, frozen_rainfall_mm, new_rainfall_mm, reason, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setString(1, code);
            ps.setTimestamp(2, Timestamp.valueOf(hourUtc));
            if (frozenRainfallVersion == null) {
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setInt(3, frozenRainfallVersion);
            }
            ps.setInt(4, newRainfallVersion);
            if (frozenRainfallMm == null) {
                ps.setNull(5, Types.DECIMAL);
            } else {
            ps.setBigDecimal(5, frozenRainfallMm);
            }
            ps.setBigDecimal(6, newRainfallMm);
            ps.setString(7, reason);
            ps.setTimestamp(8, Timestamp.valueOf(now));
            return ps;
        });
    }

    public List<Map<String, Object>> findAdjustments(String code, LocalDateTime hourUtc) {
        return jdbc.queryForList(
                "SELECT * FROM rainfall_adjustment WHERE outlet_code = ? AND hour_utc = ? ORDER BY id",
                code, Timestamp.valueOf(hourUtc));
    }
}
