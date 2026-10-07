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
 * 检修替代证据与有效监测统计数据访问层（cems-03）：全部 SQL 参数化；
 * 唯一约束承担“检修许可排口＋版本唯一、同小时当前有效替代唯一、检验报告号/窗口唯一、
 * 排口＋日结唯一、替代超标排口＋小时＋版本唯一”等并发边界。
 */
@Repository
public class MaintenanceRepository {

    private final JdbcTemplate jdbc;

    public MaintenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 检修许可版本 ----------------

    /** 现行（ACTIVE）检修许可；无则返回 null。 */
    public Map<String, Object> findActivePermit(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM maintenance_permit WHERE outlet_code = ? AND status = 'ACTIVE'", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 最新版本检修许可（不论状态）；无则返回 null。 */
    public Map<String, Object> findLatestPermit(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM maintenance_permit WHERE outlet_code = ? ORDER BY version DESC LIMIT 1", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 将现行检修许可置为 SUPERSEDED（新版本取而代之）。 */
    public void supersedeActivePermits(String code) {
        jdbc.update("UPDATE maintenance_permit SET status = 'SUPERSEDED' "
                + "WHERE outlet_code = ? AND status = 'ACTIVE'", code);
    }

    /**
     * 写入新检修许可版本（双人确认同事务写入）；(outlet_code, version) 唯一约束拒绝并发同版本。
     * @throws DuplicateKeyException 同版本已存在（并发新增同 expectedVersion）
     */
    public long insertPermit(String code, int version, LocalDateTime windowStartUtc,
                             LocalDateTime windowEndUtc, String confirmerA, String confirmerB,
                             LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO maintenance_permit(outlet_code, version, window_start_utc, window_end_utc, "
                            + "confirmer_a, confirmer_b, status, created_at, confirmed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setInt(2, version);
            ps.setTimestamp(3, Timestamp.valueOf(windowStartUtc));
            ps.setTimestamp(4, Timestamp.valueOf(windowEndUtc));
            ps.setString(5, confirmerA);
            ps.setString(6, confirmerB);
            ps.setTimestamp(7, Timestamp.valueOf(now));
            ps.setTimestamp(8, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    // ---------------- 合成检验报告 ----------------

    public Map<String, Object> findLabReport(String reportNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM lab_report WHERE report_no = ?", reportNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 登记合成检验报告；报告号全局唯一，冲突返回 false（同窗口不同报告号允许，对应不同版本证据）。 */
    public boolean insertLabReport(String reportNo, String code, LocalDateTime windowStartUtc,
                                   LocalDateTime windowEndUtc, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO lab_report(report_no, outlet_code, window_start_utc, window_end_utc, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    reportNo, code, Timestamp.valueOf(windowStartUtc), Timestamp.valueOf(windowEndUtc),
                    Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    // ---------------- 替代小时版本 ----------------

    public Map<String, Object> findReplacementByVersion(String code, LocalDateTime hourUtc, int version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM replacement_hour WHERE outlet_code = ? AND hour_utc = ? AND version = ?",
                code, Timestamp.valueOf(hourUtc), version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 当前生效（EFFECTIVE）替代版本；无则返回 null。 */
    public Map<String, Object> findEffectiveReplacement(String code, LocalDateTime hourUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM replacement_hour WHERE outlet_code = ? AND hour_utc = ? AND status = 'EFFECTIVE'",
                code, Timestamp.valueOf(hourUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 待复核（PENDING）替代版本；无则返回 null。 */
    public Map<String, Object> findPendingReplacement(String code, LocalDateTime hourUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM replacement_hour WHERE outlet_code = ? AND hour_utc = ? AND status = 'PENDING'",
                code, Timestamp.valueOf(hourUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 该小时最新替代版本（不论状态）；无记录返回 null。 */
    public Map<String, Object> findLatestReplacement(String code, LocalDateTime hourUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM replacement_hour WHERE outlet_code = ? AND hour_utc = ? ORDER BY version DESC LIMIT 1",
                code, Timestamp.valueOf(hourUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findReplacementsForHour(String code, LocalDateTime hourUtc) {
        return jdbc.queryForList(
                "SELECT * FROM replacement_hour WHERE outlet_code = ? AND hour_utc = ? ORDER BY version",
                code, Timestamp.valueOf(hourUtc));
    }

    /** 区间 [fromUtc, toUtc) 内全部替代版本（按整点、版本排序）。 */
    public List<Map<String, Object>> findReplacementsBetween(String code, LocalDateTime fromUtc,
                                                             LocalDateTime toUtc) {
        return jdbc.queryForList(
                "SELECT * FROM replacement_hour WHERE outlet_code = ? AND hour_utc >= ? AND hour_utc < ? "
                        + "ORDER BY hour_utc, version",
                code, Timestamp.valueOf(fromUtc), Timestamp.valueOf(toUtc));
    }

    /**
     * 提交新替代版本（PENDING）；(outlet_code, hour_utc, version) 唯一约束拒绝并发同版本。
     * @throws DuplicateKeyException 同版本已存在（并发提交同 expectedVersion）
     */
    public long insertReplacement(String code, LocalDateTime hourUtc, int version, BigDecimal concentration,
                                  String reportNo, String submitter, String requestId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO replacement_hour(outlet_code, hour_utc, version, concentration, report_no, "
                            + "submitter, reviewer, status, created_at, confirmed_at, request_id, active_slot) "
                            + "VALUES (?, ?, ?, ?, ?, ?, NULL, 'PENDING', ?, NULL, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setTimestamp(2, Timestamp.valueOf(hourUtc));
            ps.setInt(3, version);
            ps.setBigDecimal(4, concentration);
            ps.setString(5, reportNo);
            ps.setString(6, submitter);
            ps.setTimestamp(7, Timestamp.valueOf(now));
            ps.setString(8, requestId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 复核生效：条件更新仅当版本仍为 PENDING 时置 EFFECTIVE 并占槽（active_slot=排口编码），
     * 同小时占槽唯一约束保证最多一个当前有效版本。
     * @return 1 成功；0 版本状态已变化（已被复核/并发）
     */
    public int confirmReplacement(String code, LocalDateTime hourUtc, int version, String reviewer,
                                  LocalDateTime now) {
        return jdbc.update("UPDATE replacement_hour SET status = 'EFFECTIVE', reviewer = ?, confirmed_at = ?, "
                        + "active_slot = outlet_code WHERE outlet_code = ? AND hour_utc = ? AND version = ? "
                        + "AND status = 'PENDING'",
                reviewer, Timestamp.valueOf(now), code, Timestamp.valueOf(hourUtc), version);
    }

    /** 指定版本置 SUPERSEDED 并释放占槽（同小时新版本生效时）。 */
    public int supersedeReplacement(String code, LocalDateTime hourUtc, int version) {
        return jdbc.update("UPDATE replacement_hour SET status = 'SUPERSEDED', active_slot = NULL "
                        + "WHERE outlet_code = ? AND hour_utc = ? AND version = ? AND status = 'EFFECTIVE'",
                code, Timestamp.valueOf(hourUtc), version);
    }

    /** 指定版本置 INVALIDATED 并释放占槽（封账前补到合法机测）。 */
    public int invalidateReplacement(String code, LocalDateTime hourUtc, int version) {
        return jdbc.update("UPDATE replacement_hour SET status = 'INVALIDATED', active_slot = NULL "
                        + "WHERE outlet_code = ? AND hour_utc = ? AND version = ? AND status IN ('PENDING','EFFECTIVE')",
                code, Timestamp.valueOf(hourUtc), version);
    }

    // ---------------- 机测读数（只读 monitor_reading） ----------------

    /** 半开区间 [fromUtc, toUtc) 内合法机测小时读数（整点，按时间排序）。 */
    public List<Map<String, Object>> findMachineHoursBetween(String code, LocalDateTime fromUtc,
                                                             LocalDateTime toUtc) {
        return jdbc.queryForList(
                "SELECT * FROM monitor_reading WHERE outlet_code = ? AND period_type = 'HOUR' "
                        + "AND period_start >= ? AND period_start < ? ORDER BY period_start",
                code, Timestamp.valueOf(fromUtc), Timestamp.valueOf(toUtc));
    }

    // ---------------- 日结冻结快照 ----------------

    public Map<String, Object> findReview(String code, LocalDate dayUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM maintenance_review WHERE outlet_code = ? AND day_utc = ?",
                code, Date.valueOf(dayUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 冻结有效监测日结；(outlet_code, day_utc) 唯一约束保证每日仅封账一次。空集合均值写 NULL。
     * @throws DuplicateKeyException 并发重复封账
     */
    public long insertReview(String code, LocalDate dayUtc, Integer permitVersion, BigDecimal effectiveAvg,
                             int countedCount, int missingCount, String requestId, LocalDateTime frozenAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO maintenance_review(outlet_code, day_utc, permit_version, effective_avg, "
                            + "counted_count, missing_count, status, frozen_at, request_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'FROZEN', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setDate(2, Date.valueOf(dayUtc));
            if (permitVersion == null) {
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setInt(3, permitVersion);
            }
            if (effectiveAvg == null) {
                ps.setNull(4, Types.DECIMAL);
            } else {
                ps.setBigDecimal(4, effectiveAvg);
            }
            ps.setInt(5, countedCount);
            ps.setInt(6, missingCount);
            ps.setTimestamp(7, Timestamp.valueOf(frozenAt));
            if (requestId == null) {
                ps.setNull(8, Types.VARCHAR);
            } else {
                ps.setString(8, requestId);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 写入封账逐小时选择快照。 */
    public void insertReviewItem(String code, LocalDate dayUtc, LocalDateTime hourUtc, String source,
                                 BigDecimal concentration, Integer replacementVersion, String reportNo) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO maintenance_review_item(outlet_code, day_utc, hour_utc, source, concentration, "
                            + "replacement_version, report_no) VALUES (?, ?, ?, ?, ?, ?, ?)");
            ps.setString(1, code);
            ps.setDate(2, Date.valueOf(dayUtc));
            ps.setTimestamp(3, Timestamp.valueOf(hourUtc));
            ps.setString(4, source);
            ps.setBigDecimal(5, concentration);
            if (replacementVersion == null) {
                ps.setNull(6, Types.INTEGER);
            } else {
                ps.setInt(6, replacementVersion);
            }
            if (reportNo == null) {
                ps.setNull(7, Types.VARCHAR);
            } else {
                ps.setString(7, reportNo);
            }
            return ps;
        });
    }

    public List<Map<String, Object>> findReviewItems(String code, LocalDate dayUtc) {
        return jdbc.queryForList(
                "SELECT * FROM maintenance_review_item WHERE outlet_code = ? AND day_utc = ? ORDER BY hour_utc",
                code, Date.valueOf(dayUtc));
    }

    // ---------------- 封账后独立调差 ----------------

    public long insertAdjustment(String code, LocalDate dayUtc, LocalDateTime hourUtc, String changeType,
                                 String oldSource, BigDecimal oldValue, String newSource, BigDecimal newValue,
                                 Integer replacementVersion, String requestId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO maintenance_adjustment(outlet_code, day_utc, hour_utc, change_type, old_source, "
                            + "old_value, new_source, new_value, replacement_version, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setDate(2, Date.valueOf(dayUtc));
            ps.setTimestamp(3, Timestamp.valueOf(hourUtc));
            ps.setString(4, changeType);
            if (oldSource == null) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, oldSource);
            }
            if (oldValue == null) {
                ps.setNull(6, Types.DECIMAL);
            } else {
                ps.setBigDecimal(6, oldValue);
            }
            ps.setString(7, newSource);
            ps.setBigDecimal(8, newValue);
            if (replacementVersion == null) {
                ps.setNull(9, Types.INTEGER);
            } else {
                ps.setInt(9, replacementVersion);
            }
            if (requestId == null) {
                ps.setNull(10, Types.VARCHAR);
            } else {
                ps.setString(10, requestId);
            }
            ps.setTimestamp(11, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<Map<String, Object>> findAdjustments(String code, LocalDate dayUtc) {
        return jdbc.queryForList(
                "SELECT * FROM maintenance_adjustment WHERE outlet_code = ? AND day_utc = ? ORDER BY id",
                code, Date.valueOf(dayUtc));
    }

    // ---------------- 替代超标独立来源 ----------------

    public Map<String, Object> findSubstituteExceedance(String code, LocalDateTime hourUtc, int version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM substitute_exceedance WHERE outlet_code = ? AND hour_utc = ? AND replacement_version = ?",
                code, Timestamp.valueOf(hourUtc), version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 追加替代超标独立来源（不去重历史；同排口＋小时＋版本唯一）。 */
    public void insertSubstituteExceedance(String code, LocalDateTime hourUtc, int version,
                                           BigDecimal limitValue, BigDecimal actualValue, LocalDateTime now) {
        jdbc.update("INSERT INTO substitute_exceedance(outlet_code, hour_utc, replacement_version, limit_value, "
                        + "actual_value, status, created_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)",
                code, Timestamp.valueOf(hourUtc), version, limitValue, actualValue, Timestamp.valueOf(now));
    }

    /** 同小时其余生效中超标来源置 SUPERSEDED（新替代版本生效时），保留当前版本。 */
    public int supersedeOtherActiveSubstituteExceedances(String code, LocalDateTime hourUtc, int keepVersion) {
        return jdbc.update("UPDATE substitute_exceedance SET status = 'SUPERSEDED' "
                        + "WHERE outlet_code = ? AND hour_utc = ? AND replacement_version <> ? AND status = 'ACTIVE'",
                code, Timestamp.valueOf(hourUtc), keepVersion);
    }

    /** 同小时全部生效中替代超标来源置 INVALIDATED（补到合法机测时）。 */
    public int invalidateActiveSubstituteExceedances(String code, LocalDateTime hourUtc) {
        return jdbc.update("UPDATE substitute_exceedance SET status = 'INVALIDATED' "
                        + "WHERE outlet_code = ? AND hour_utc = ? AND status = 'ACTIVE'",
                code, Timestamp.valueOf(hourUtc));
    }

    public List<Map<String, Object>> findSubstituteExceedances(String code) {
        return jdbc.queryForList(
                "SELECT * FROM substitute_exceedance WHERE outlet_code = ? ORDER BY hour_utc, replacement_version",
                code);
    }
}
