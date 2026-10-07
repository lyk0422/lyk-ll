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
 * 检修替代监测数据访问层：全部 SQL 参数化；
 * 唯一约束承担“排口＋许可版本唯一、报告编号唯一、排口＋整点＋替代版本唯一、
 * 排口＋日结唯一、排口＋日＋整点槽位唯一”等并发边界。
 */
@Repository
public class SubstituteRepository {

    private final JdbcTemplate jdbc;

    public SubstituteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 检修许可版本 ----------------

    /** 最新版本许可（不论状态）；无则返回 null。 */
    public Map<String, Object> findLatestPermit(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM maintenance_permit WHERE outlet_code = ? ORDER BY version DESC LIMIT 1", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findPermit(String code, int version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM maintenance_permit WHERE outlet_code = ? AND version = ?", code, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 现行（ACTIVE）许可；无则返回 null。 */
    public Map<String, Object> findActivePermit(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM maintenance_permit WHERE outlet_code = ? AND status = 'ACTIVE'", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 将该排口未取代的许可全部置为 SUPERSEDED（新版本取而代之）。 */
    public void supersedeOpenPermits(String code) {
        jdbc.update("UPDATE maintenance_permit SET status = 'SUPERSEDED' "
                + "WHERE outlet_code = ? AND status <> 'SUPERSEDED'", code);
    }

    /**
     * 写入新许可版本（初始 PENDING_CONFIRM）；(outlet_code, version) 唯一约束拒绝并发同版本写入。
     * @throws DuplicateKeyException 同版本已存在（并发新增同 expectedVersion）
     */
    public long insertPermit(String code, int version, LocalDateTime windowStartUtc,
                             LocalDateTime windowEndUtc, String createdBy, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO maintenance_permit(outlet_code, version, window_start_utc, window_end_utc, "
                            + "status, created_by, created_at) VALUES (?, ?, ?, ?, 'PENDING_CONFIRM', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setInt(2, version);
            ps.setTimestamp(3, Timestamp.valueOf(windowStartUtc));
            ps.setTimestamp(4, Timestamp.valueOf(windowEndUtc));
            ps.setString(5, createdBy);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 许可生效：仅待确认版本可激活，返回 0 表示状态已变化。 */
    public int activatePermit(String code, int version) {
        return jdbc.update("UPDATE maintenance_permit SET status = 'ACTIVE' "
                + "WHERE outlet_code = ? AND version = ? AND status = 'PENDING_CONFIRM'", code, version);
    }

    // ---------------- 许可两人确认 ----------------

    /**
     * 登记确认人；(outlet_code, permit_version, confirmer) 唯一约束拒绝同人重复确认。
     * @return true 新确认；false 该确认人已确认过
     */
    public boolean insertConfirm(String code, int permitVersion, String confirmer, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO maintenance_permit_confirm(outlet_code, permit_version, confirmer, "
                            + "created_at) VALUES (?, ?, ?, ?)",
                    code, permitVersion, confirmer, Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public List<String> findConfirmers(String code, int permitVersion) {
        return jdbc.queryForList(
                "SELECT confirmer FROM maintenance_permit_confirm "
                        + "WHERE outlet_code = ? AND permit_version = ? ORDER BY id",
                String.class, code, permitVersion);
    }

    // ---------------- 合成检验报告 ----------------

    /**
     * 登记合成检验报告；报告编号唯一约束拒绝重复编号。
     * @throws DuplicateKeyException 报告编号已存在
     */
    public long insertReport(String reportNo, String code, LocalDateTime hourUtc, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO lab_report(report_no, outlet_code, hour_utc, created_at) "
                            + "VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, reportNo);
            ps.setString(2, code);
            ps.setTimestamp(3, Timestamp.valueOf(hourUtc));
            ps.setTimestamp(4, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Map<String, Object> findReport(String reportNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM lab_report WHERE report_no = ?", reportNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- 替代监测小时台账 ----------------

    /** 该小时最新版本替代（不论状态）；无则返回 null。 */
    public Map<String, Object> findLatestSubstitute(String code, LocalDateTime hourUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM substitute_hour WHERE outlet_code = ? AND hour_utc = ? "
                        + "ORDER BY version DESC LIMIT 1",
                code, Timestamp.valueOf(hourUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findSubstitute(String code, LocalDateTime hourUtc, int version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM substitute_hour WHERE outlet_code = ? AND hour_utc = ? AND version = ?",
                code, Timestamp.valueOf(hourUtc), version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 半开区间 [fromUtc, toUtc) 内的替代台账（按整点、版本升序）。 */
    public List<Map<String, Object>> findSubstitutesBetween(String code, LocalDateTime fromUtc,
                                                            LocalDateTime toUtc) {
        return jdbc.queryForList(
                "SELECT * FROM substitute_hour WHERE outlet_code = ? AND hour_utc >= ? AND hour_utc < ? "
                        + "ORDER BY hour_utc, version",
                code, Timestamp.valueOf(fromUtc), Timestamp.valueOf(toUtc));
    }

    public List<Map<String, Object>> findSubstitutes(String code) {
        return jdbc.queryForList(
                "SELECT * FROM substitute_hour WHERE outlet_code = ? ORDER BY hour_utc, version", code);
    }

    /**
     * 写入新替代版本（初始 PENDING_REVIEW）；(outlet_code, hour_utc, version) 唯一约束拒绝并发同版本写入。
     * @throws DuplicateKeyException 同小时同版本已存在（并发更正同 expectedVersion）
     */
    public long insertSubstitute(String code, LocalDateTime hourUtc, int version, String reportNo,
                                 BigDecimal value, String submittedBy, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO substitute_hour(outlet_code, hour_utc, version, report_no, sub_value, "
                            + "submitted_by, status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING_REVIEW', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setTimestamp(2, Timestamp.valueOf(hourUtc));
            ps.setInt(3, version);
            ps.setString(4, reportNo);
            ps.setBigDecimal(5, value);
            ps.setString(6, submittedBy);
            ps.setTimestamp(7, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 旧版本被取代：仅当前非终态版本可置 SUPERSEDED，返回影响行数。 */
    public int supersedeSubstitute(long id) {
        return jdbc.update("UPDATE substitute_hour SET status = 'SUPERSEDED' "
                + "WHERE id = ? AND status IN ('PENDING_REVIEW', 'EFFECTIVE')", id);
    }

    /** 复核生效：仅待复核版本可生效，返回 0 表示状态已变化。 */
    public int markEffective(long id, String reviewer, LocalDateTime now) {
        return jdbc.update("UPDATE substitute_hour SET status = 'EFFECTIVE', reviewed_by = ?, "
                        + "reviewed_at = ? WHERE id = ? AND status = 'PENDING_REVIEW'",
                reviewer, Timestamp.valueOf(now), id);
    }

    /** 封账前补到合法机测：该小时生效中替代置 INVALIDATED（留证不删除），返回影响行数。 */
    public int invalidateEffective(String code, LocalDateTime hourUtc) {
        return jdbc.update("UPDATE substitute_hour SET status = 'INVALIDATED' "
                        + "WHERE outlet_code = ? AND hour_utc = ? AND status = 'EFFECTIVE'",
                code, Timestamp.valueOf(hourUtc));
    }

    // ---------------- 替代超标独立来源（只留证不能删除） ----------------

    public long insertExceedance(String code, LocalDateTime hourUtc, BigDecimal limitValue,
                                 BigDecimal actualValue, int substituteVersion, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO substitute_exceedance(outlet_code, hour_utc, limit_value, actual_value, "
                            + "substitute_version, status, created_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setTimestamp(2, Timestamp.valueOf(hourUtc));
            ps.setBigDecimal(3, limitValue);
            ps.setBigDecimal(4, actualValue);
            ps.setInt(5, substituteVersion);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 该小时生效中的替代超标来源置 SUPERSEDED（被机测或新替代版本取代；历史行保留）。 */
    public int supersedeActiveExceedances(String code, LocalDateTime hourUtc) {
        return jdbc.update("UPDATE substitute_exceedance SET status = 'SUPERSEDED' "
                        + "WHERE outlet_code = ? AND hour_utc = ? AND status = 'ACTIVE'",
                code, Timestamp.valueOf(hourUtc));
    }

    public List<Map<String, Object>> findExceedances(String code) {
        return jdbc.queryForList(
                "SELECT * FROM substitute_exceedance WHERE outlet_code = ? ORDER BY id", code);
    }

    // ---------------- 有效监测日结冻结 ----------------

    public Map<String, Object> findFreeze(String code, LocalDate dayUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM effective_freeze WHERE outlet_code = ? AND day_utc = ?",
                code, Date.valueOf(dayUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 冻结日结快照；(outlet_code, day_utc) 唯一约束保证每日仅冻结一次。
     * 均值空集合写 NULL（无结果而非零）。
     */
    public long insertFreeze(String code, LocalDate dayUtc, BigDecimal avgValue, int machineCount,
                             int substituteCount, int missingCount, String requestId,
                             LocalDateTime frozenAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO effective_freeze(outlet_code, day_utc, avg_value, machine_count, "
                            + "substitute_count, missing_count, status, frozen_at, request_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'FROZEN', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setDate(2, Date.valueOf(dayUtc));
            if (avgValue == null) {
                ps.setNull(3, Types.DECIMAL);
            } else {
                ps.setBigDecimal(3, avgValue);
            }
            ps.setInt(4, machineCount);
            ps.setInt(5, substituteCount);
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

    public void insertFreezeItem(long freezeId, String code, LocalDate dayUtc, LocalDateTime hourUtc,
                                 String source, BigDecimal value, Integer substituteVersion) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO effective_freeze_item(freeze_id, outlet_code, day_utc, hour_utc, source, "
                            + "item_value, substitute_version) VALUES (?, ?, ?, ?, ?, ?, ?)");
            ps.setLong(1, freezeId);
            ps.setString(2, code);
            ps.setDate(3, Date.valueOf(dayUtc));
            ps.setTimestamp(4, Timestamp.valueOf(hourUtc));
            ps.setString(5, source);
            if (value == null) {
                ps.setNull(6, Types.DECIMAL);
            } else {
                ps.setBigDecimal(6, value);
            }
            if (substituteVersion == null) {
                ps.setNull(7, Types.INTEGER);
            } else {
                ps.setInt(7, substituteVersion);
            }
            return ps;
        });
    }

    public List<Map<String, Object>> findFreezeItems(long freezeId) {
        return jdbc.queryForList(
                "SELECT * FROM effective_freeze_item WHERE freeze_id = ? ORDER BY hour_utc", freezeId);
    }

    /** 冻结快照中某整点槽位；无快照或无槽位返回 null。 */
    public Map<String, Object> findFreezeItem(String code, LocalDate dayUtc, LocalDateTime hourUtc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM effective_freeze_item WHERE outlet_code = ? AND day_utc = ? AND hour_utc = ?",
                code, Date.valueOf(dayUtc), Timestamp.valueOf(hourUtc));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- 独立调差链（冻结后） ----------------

    public long insertAdjustment(String code, LocalDate dayUtc, LocalDateTime hourUtc, String adjustType,
                                 String oldSource, BigDecimal oldValue, String newSource,
                                 BigDecimal newValue, Integer substituteVersion, String requestId,
                                 LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO effective_adjustment(outlet_code, day_utc, hour_utc, adjust_type, "
                            + "old_source, old_value, new_source, new_value, substitute_version, "
                            + "request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setDate(2, Date.valueOf(dayUtc));
            ps.setTimestamp(3, Timestamp.valueOf(hourUtc));
            ps.setString(4, adjustType);
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
            if (substituteVersion == null) {
                ps.setNull(9, Types.INTEGER);
            } else {
                ps.setInt(9, substituteVersion);
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
                "SELECT * FROM effective_adjustment WHERE outlet_code = ? AND day_utc = ? ORDER BY id",
                code, Date.valueOf(dayUtc));
    }
}
