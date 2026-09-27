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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 雨季溢流数据访问层：全部 SQL 均参数化。
 * 许可版本唯一约束（排口＋版本）承担并发变更唯一受理；日结快照唯一约束（排口＋UTC日）
 * 承担“标记复核与日结竞争冻结、先冻者为准”。
 */
@Repository
public class OverflowRepository {

    private final JdbcTemplate jdbc;

    public OverflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 溢流许可版本 ----------------

    /** 写入许可新版本；同排口同版本并发写入由唯一约束拒绝。 */
    public void insertPermit(String code, int version, LocalDateTime validFrom, LocalDateTime validTo,
                             BigDecimal minRainfall, int maxConsecutiveHours, LocalDateTime now) {
        jdbc.update("INSERT INTO overflow_permit(outlet_code, version, valid_from, valid_to, min_rainfall, "
                        + "max_consecutive_hours, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                code, version, Timestamp.valueOf(validFrom), Timestamp.valueOf(validTo), minRainfall,
                maxConsecutiveHours, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    /** 当前许可版本号；无许可返回 0。 */
    public int currentPermitVersion(String code) {
        Integer v = jdbc.queryForObject(
                "SELECT MAX(version) FROM overflow_permit WHERE outlet_code = ?", Integer.class, code);
        return v == null ? 0 : v;
    }

    /** 当前许可（最高版本）；无许可返回 null。 */
    public Map<String, Object> findCurrentPermit(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_permit WHERE outlet_code = ? ORDER BY version DESC LIMIT 1", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按版本查找许可（连续段重建时取该段许可的最大连续小时数）。 */
    public Map<String, Object> findPermitByVersion(String code, int version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_permit WHERE outlet_code = ? AND version = ?", code, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- 小时溢流数据 ----------------

    public Map<String, Object> findHourly(String code, LocalDateTime hour) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_hourly WHERE outlet_code = ? AND hour_start = ?",
                code, Timestamp.valueOf(hour));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 写入小时溢流数据；排口＋UTC整点唯一约束去重。 */
    public void insertHourly(String code, LocalDateTime hour, BigDecimal rainfall, int rainfallVersion,
                             boolean overflowFlag, String evalStatus, Integer permitVersion, LocalDateTime now) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO overflow_hourly(outlet_code, hour_start, rainfall, rainfall_version, "
                            + "overflow_flag, eval_status, permit_version, reviewed, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?)");
            ps.setString(1, code);
            ps.setTimestamp(2, Timestamp.valueOf(hour));
            if (rainfall == null) {
                ps.setNull(3, Types.DECIMAL);
            } else {
                ps.setBigDecimal(3, rainfall);
            }
            ps.setInt(4, rainfallVersion);
            ps.setBoolean(5, overflowFlag);
            ps.setString(6, evalStatus);
            if (permitVersion == null) {
                ps.setNull(7, Types.INTEGER);
            } else {
                ps.setInt(7, permitVersion);
            }
            ps.setTimestamp(8, Timestamp.valueOf(now));
            ps.setTimestamp(9, Timestamp.valueOf(now));
            return ps;
        });
    }

    /** 更正小时溢流数据（雨量版本由调用方计算）；仅允许在未复核冻结前调用。 */
    public void updateHourly(String code, LocalDateTime hour, BigDecimal rainfall, int rainfallVersion,
                             boolean overflowFlag, String evalStatus, Integer permitVersion, LocalDateTime now) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "UPDATE overflow_hourly SET rainfall = ?, rainfall_version = ?, overflow_flag = ?, "
                            + "eval_status = ?, permit_version = ?, updated_at = ? "
                            + "WHERE outlet_code = ? AND hour_start = ? AND reviewed = FALSE");
            if (rainfall == null) {
                ps.setNull(1, Types.DECIMAL);
            } else {
                ps.setBigDecimal(1, rainfall);
            }
            ps.setInt(2, rainfallVersion);
            ps.setBoolean(3, overflowFlag);
            ps.setString(4, evalStatus);
            if (permitVersion == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setInt(5, permitVersion);
            }
            ps.setTimestamp(6, Timestamp.valueOf(now));
            ps.setString(7, code);
            ps.setTimestamp(8, Timestamp.valueOf(hour));
            return ps;
        });
    }

    /** 区间内全部小时溢流数据（含区间端点语义 [from, to)）。 */
    public List<Map<String, Object>> findHourlyInRange(String code, LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_hourly WHERE outlet_code = ? AND hour_start >= ? AND hour_start < ? "
                        + "ORDER BY hour_start",
                code, Timestamp.valueOf(from), Timestamp.valueOf(to));
    }

    /** 排口全部小时溢流数据（连续段重建用）。 */
    public List<Map<String, Object>> findAllHourly(String code) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_hourly WHERE outlet_code = ? ORDER BY hour_start", code);
    }

    /** 区间内证据齐全的合规溢流小时（正常口径排除用）。 */
    public List<Map<String, Object>> findCompliantHours(String code, LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_hourly WHERE outlet_code = ? AND eval_status = 'COMPLIANT' "
                        + "AND hour_start >= ? AND hour_start < ? ORDER BY hour_start",
                code, Timestamp.valueOf(from), Timestamp.valueOf(to));
    }

    /** 复核冻结：标记区间内全部小时为已复核（冻结所用许可及雨量版本）。 */
    public int markReviewed(String code, LocalDateTime from, LocalDateTime to) {
        return jdbc.update(
                "UPDATE overflow_hourly SET reviewed = TRUE WHERE outlet_code = ? "
                        + "AND hour_start >= ? AND hour_start < ? AND reviewed = FALSE",
                code, Timestamp.valueOf(from), Timestamp.valueOf(to));
    }

    // ---------------- 日结快照（双口径冻结） ----------------

    /**
     * 写入日结快照；排口＋UTC日唯一约束保证复核与日结竞争时仅一个冻结成功。
     * @return true 本次冻结成功；false 已存在冻结快照（竞争失败，调用方读取已冻结结果）
     */
    public boolean insertSnapshot(String code, LocalDateTime dayStart, Integer permitVersion,
                                  String rainfallVersionsJson, BigDecimal normalAvg, int normalCount,
                                  BigDecimal allAvg, int allCount, String missingHoursJson,
                                  String frozenBy, String requestId, LocalDateTime now) {
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO overflow_daily_snapshot(outlet_code, day_start, permit_version, "
                                + "rainfall_versions, normal_avg, normal_count, all_avg, all_count, "
                                + "missing_hours, frozen_by, request_id, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                ps.setString(1, code);
                ps.setTimestamp(2, Timestamp.valueOf(dayStart));
                if (permitVersion == null) {
                    ps.setNull(3, Types.INTEGER);
                } else {
                    ps.setInt(3, permitVersion);
                }
                ps.setString(4, rainfallVersionsJson);
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
                ps.setString(9, missingHoursJson);
                ps.setString(10, frozenBy);
                ps.setString(11, requestId);
                ps.setTimestamp(12, Timestamp.valueOf(now));
                return ps;
            });
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Map<String, Object> findSnapshot(String code, LocalDateTime dayStart) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM overflow_daily_snapshot WHERE outlet_code = ? AND day_start = ?",
                code, Timestamp.valueOf(dayStart));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- 专项异常 ----------------

    public void insertAnomaly(String code, LocalDateTime segmentStart, LocalDateTime segmentEnd,
                              int segmentHours, int maxAllowed, LocalDateTime now) {
        jdbc.update("INSERT INTO overflow_anomaly(outlet_code, segment_start, segment_end, segment_hours, "
                        + "max_allowed, status, created_at) VALUES (?, ?, ?, ?, ?, 'OPEN', ?)",
                code, Timestamp.valueOf(segmentStart), Timestamp.valueOf(segmentEnd), segmentHours,
                maxAllowed, Timestamp.valueOf(now));
    }

    public List<Map<String, Object>> findAnomalies(String code) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_anomaly WHERE outlet_code = ? ORDER BY segment_start", code);
    }

    /** 复核时重建连续段：删除旧专项异常后按最新评估重算。 */
    public int deleteAnomalies(String code) {
        return jdbc.update("DELETE FROM overflow_anomaly WHERE outlet_code = ?", code);
    }

    // ---------------- 独立调差 ----------------

    public long insertAdjustment(String code, LocalDateTime hour, BigDecimal adjustValue, String reason,
                                 String requestId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO overflow_adjustment(outlet_code, hour_start, adjust_value, reason, request_id, "
                            + "created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setTimestamp(2, Timestamp.valueOf(hour));
            ps.setBigDecimal(3, adjustValue);
            ps.setString(4, reason);
            ps.setString(5, requestId);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<Map<String, Object>> findAdjustments(String code) {
        return jdbc.queryForList(
                "SELECT * FROM overflow_adjustment WHERE outlet_code = ? ORDER BY id", code);
    }

    // ---------------- 原排放浓度读数（只读，双口径统计用） ----------------

    /** 区间内小时浓度读数；原排放浓度永远保留，溢流标记不修改、不删除读数。 */
    public List<Map<String, Object>> findConcentrationReadings(String code, LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList(
                "SELECT * FROM monitor_reading WHERE outlet_code = ? AND period_type = 'HOUR' "
                        + "AND period_start >= ? AND period_start < ? ORDER BY period_start",
                code, Timestamp.valueOf(from), Timestamp.valueOf(to));
    }
}
