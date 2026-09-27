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
 * 雨季溢流证据数据访问层：全部 SQL 参数化；
 * 唯一约束承担“排口＋整点去重、排口＋许可版本唯一、排口＋日结唯一、并发占键拒绝”等边界。
 */
@Repository
public class OverflowRepository {

    private final JdbcTemplate jdbc;

    public OverflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 溢流许可版本 ----------------

    /** 现行（ACTIVE）许可；无则返回 null。 */
    public Map<String, Object> findActivePermit(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_permit WHERE outlet_code = ? AND status = 'ACTIVE'", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 最新版本许可（不论状态）；无则返回 null。 */
    public Map<String, Object> findLatestPermit(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_permit WHERE outlet_code = ? ORDER BY version DESC LIMIT 1", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findPermits(String code) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_permit WHERE outlet_code = ? ORDER BY version", code);
    }

    /** 将现行许可置为 SUPERSEDED（新版本取而代之）。 */
    public void supersedeActivePermits(String code) {
        jdbc.update("UPDATE overflow_permit SET status = 'SUPERSEDED' "
                + "WHERE outlet_code = ? AND status = 'ACTIVE'", code);
    }

    /**
     * 写入新许可版本；(outlet_code, version) 唯一约束拒绝并发同版本写入。
     * @throws DuplicateKeyException 同版本已存在（并发新增同 expectedVersion）
     */
    public long insertPermit(String code, int version, LocalDateTime windowStartUtc,
                             LocalDateTime windowEndUtc, BigDecimal minRainfallMm,
                             int maxConsecutiveHours, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO overflow_permit(outlet_code, version, window_start_utc, window_end_utc, "
                            + "min_rainfall_mm, max_consecutive_hours, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setInt(2, version);
            ps.setTimestamp(3, Timestamp.valueOf(windowStartUtc));
            ps.setTimestamp(4, Timestamp.valueOf(windowEndUtc));
            ps.setBigDecimal(5, minRainfallMm);
            ps.setInt(6, maxConsecutiveHours);
            ps.setTimestamp(7, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    // ---------------- 溢流小时数据 ----------------

    public Map<String, Object> findHour(String code, LocalDateTime hourUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_hour WHERE outlet_code = ? AND hour_utc = ?",
                code, Timestamp.valueOf(hourUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 写入小时数据；(outlet_code, hour_utc) 唯一约束去重。
     * @return true 新写入；false 该整点已存在
     */
    public boolean insertHour(String code, LocalDateTime hourUtc, BigDecimal rainfallMm,
                              boolean overflowFlag, BigDecimal avgValue, String markStatus,
                              Integer permitVersion, LocalDateTime now) {
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO overflow_hour(outlet_code, hour_utc, rainfall_mm, overflow_flag, "
                                + "avg_value, mark_status, permit_version, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
                ps.setString(1, code);
                ps.setTimestamp(2, Timestamp.valueOf(hourUtc));
                if (rainfallMm == null) {
                    ps.setNull(3, Types.DECIMAL);
                } else {
                    ps.setBigDecimal(3, rainfallMm);
                }
                ps.setBoolean(4, overflowFlag);
                ps.setBigDecimal(5, avgValue);
                ps.setString(6, markStatus);
                if (permitVersion == null) {
                    ps.setNull(7, Types.INTEGER);
                } else {
                    ps.setInt(7, permitVersion);
                }
                ps.setTimestamp(8, Timestamp.valueOf(now));
                ps.setTimestamp(9, Timestamp.valueOf(now));
                return ps;
            });
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public List<Map<String, Object>> findHours(String code) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_hour WHERE outlet_code = ? ORDER BY hour_utc", code);
    }

    /** 半开区间 [fromUtc, toUtc) 内的小时数据。 */
    public List<Map<String, Object>> findHoursBetween(String code, LocalDateTime fromUtc,
                                                      LocalDateTime toUtc) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_hour WHERE outlet_code = ? AND hour_utc >= ? AND hour_utc < ? "
                        + "ORDER BY hour_utc",
                code, Timestamp.valueOf(fromUtc), Timestamp.valueOf(toUtc));
    }

    /** 复核前更正雨量：更新雨量并按当前许可重算标记（同事务）。 */
    public void updateHourRainfallAndMark(String code, LocalDateTime hourUtc, BigDecimal rainfallMm,
                                         String markStatus, Integer permitVersion, LocalDateTime now) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "UPDATE overflow_hour SET rainfall_mm = ?, mark_status = ?, permit_version = ?, "
                            + "updated_at = ? WHERE outlet_code = ? AND hour_utc = ?");
            ps.setBigDecimal(1, rainfallMm);
            ps.setString(2, markStatus);
            if (permitVersion == null) {
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setInt(3, permitVersion);
            }
            ps.setTimestamp(4, Timestamp.valueOf(now));
            ps.setString(5, code);
            ps.setTimestamp(6, Timestamp.valueOf(hourUtc));
            return ps;
        });
    }

    // ---------------- 雨量版本计数 ----------------

    /** 当前雨量版本；无记录（尚无小时数据）返回 null。 */
    public Integer findRainfallVersion(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rainfall_version FROM overflow_counter WHERE outlet_code = ?", code);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("rainfall_version")).intValue();
    }

    /** 首次上报小时数据时初始化雨量版本为 1；并发由主键拒绝后忽略。 */
    public void ensureCounter(String code, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO overflow_counter(outlet_code, rainfall_version, updated_at) "
                    + "VALUES (?, 1, ?)", code, Timestamp.valueOf(now));
        } catch (DuplicateKeyException e) {
            // 已存在：保持现版本
        }
    }

    /**
     * 条件递增雨量版本（乐观并发）：仅当当前版本等于 expected 时置为 expected+1。
     * @return 1 成功；0 版本已变化（调用方判 409）
     */
    public int bumpRainfallVersion(String code, int expected, LocalDateTime now) {
        return jdbc.update("UPDATE overflow_counter SET rainfall_version = ?, updated_at = ? "
                        + "WHERE outlet_code = ? AND rainfall_version = ?",
                expected + 1, Timestamp.valueOf(now), code, expected);
    }

    // ---------------- 日结复核快照 ----------------

    public Map<String, Object> findReview(String code, LocalDate dayUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_review WHERE outlet_code = ? AND day_utc = ?",
                code, Date.valueOf(dayUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 冻结日结快照；(outlet_code, day_utc) 唯一约束保证每日仅冻结一次。
     * 均值空集合写 NULL（无结果而非零）。
     */
    public long insertReview(String code, LocalDate dayUtc, Integer permitVersion, int rainfallVersion,
                             BigDecimal normalAvg, int normalCount, BigDecimal allAvg, int allCount,
                             int missingCount, String requestId, LocalDateTime frozenAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO overflow_review(outlet_code, day_utc, permit_version, rainfall_version, "
                            + "normal_avg, normal_count, all_avg, all_count, missing_count, status, "
                            + "frozen_at, request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'FROZEN', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setDate(2, Date.valueOf(dayUtc));
            if (permitVersion == null) {
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setInt(3, permitVersion);
            }
            ps.setInt(4, rainfallVersion);
            if (normalAvg == null) {
                ps.setNull(5, Types.DECIMAL);
            } else {
                ps.setBigDecimal(5, normalAvg);
            }
            ps.setInt(6, normalCount);
            if (allAvg == null) {
                ps.setNull(7, Types.DECIMAL);
            } else {
                ps.setBigDecimal(7, allAvg);
            }
            ps.setInt(8, allCount);
            ps.setInt(9, missingCount);
            ps.setTimestamp(10, Timestamp.valueOf(frozenAt));
            if (requestId == null) {
                ps.setNull(11, Types.VARCHAR);
            } else {
                ps.setString(11, requestId);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    // ---------------- 独立调差（冻结后） ----------------

    public long insertAdjustment(String code, LocalDate dayUtc, LocalDateTime hourUtc,
                                 BigDecimal oldRainfallMm, BigDecimal newRainfallMm,
                                 int rainfallVersion, String requestId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO overflow_adjustment(outlet_code, day_utc, hour_utc, old_rainfall_mm, "
                            + "new_rainfall_mm, rainfall_version, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setDate(2, Date.valueOf(dayUtc));
            ps.setTimestamp(3, Timestamp.valueOf(hourUtc));
            if (oldRainfallMm == null) {
                ps.setNull(4, Types.DECIMAL);
            } else {
                ps.setBigDecimal(4, oldRainfallMm);
            }
            ps.setBigDecimal(5, newRainfallMm);
            ps.setInt(6, rainfallVersion);
            if (requestId == null) {
                ps.setNull(7, Types.VARCHAR);
            } else {
                ps.setString(7, requestId);
            }
            ps.setTimestamp(8, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<Map<String, Object>> findAdjustments(String code, LocalDate dayUtc) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_adjustment WHERE outlet_code = ? AND day_utc = ? ORDER BY id",
                code, Date.valueOf(dayUtc));
    }

    // ---------------- 专项异常 ----------------

    /** 重算分段：删除该排口全部专项异常后按当前小时数据重建（数据未变则同键重建，幂等）。 */
    public int deleteAnomalies(String code) {
        return jdbc.update("DELETE FROM overflow_anomaly WHERE outlet_code = ?", code);
    }

    public void insertAnomaly(String code, LocalDateTime segmentStartUtc, LocalDateTime segmentEndUtc,
                              int hourCount, int maxAllowed, int permitVersion, String reason,
                              LocalDateTime now) {
        jdbc.update("INSERT INTO overflow_anomaly(outlet_code, segment_start_utc, segment_end_utc, "
                        + "hour_count, max_allowed, permit_version, reason, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)",
                code, Timestamp.valueOf(segmentStartUtc), Timestamp.valueOf(segmentEndUtc),
                hourCount, maxAllowed, permitVersion, reason, Timestamp.valueOf(now));
    }

    public List<Map<String, Object>> findAnomalies(String code) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_anomaly WHERE outlet_code = ? ORDER BY segment_start_utc", code);
    }
}
