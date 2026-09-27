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
 * 数据访问层：全部 SQL 均参数化；唯一约束承担“读数去重、执行中限排令唯一受理”等并发边界。
 */
@Repository
public class CemsRepository {

    private final JdbcTemplate jdbc;

    public CemsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 排口 ----------------

    /** 登记排口；排口编码重复由唯一约束拒绝。 */
    public long insertOutlet(String code, String name, BigDecimal dailyLimit, BigDecimal hourlyLimit,
                             String unit, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO discharge_outlet(outlet_code, outlet_name, status, daily_limit, hourly_limit, "
                            + "limit_unit, created_at, updated_at) VALUES (?, ?, 'REGISTERED', ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setBigDecimal(3, dailyLimit);
            ps.setBigDecimal(4, hourlyLimit);
            ps.setString(5, unit);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            ps.setTimestamp(7, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Map<String, Object> findOutlet(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM discharge_outlet WHERE outlet_code = ?", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 排口进入在线监测（许可登记后首次接入监测数据时调用）。 */
    public void markMonitoring(String code, LocalDateTime now) {
        jdbc.update("UPDATE discharge_outlet SET status = 'MONITORING', updated_at = ? "
                + "WHERE outlet_code = ? AND status = 'REGISTERED'", Timestamp.valueOf(now), code);
    }

    public void updateOutletStatus(String code, String status, LocalDateTime now) {
        jdbc.update("UPDATE discharge_outlet SET status = ?, updated_at = ? WHERE outlet_code = ?",
                status, Timestamp.valueOf(now), code);
    }

    public int archiveOutlet(String code, LocalDateTime archivedAt) {
        return jdbc.update(
                "UPDATE discharge_outlet SET status = 'ARCHIVED', archived_at = ?, updated_at = ? "
                        + "WHERE outlet_code = ? AND status <> 'ARCHIVED'",
                Timestamp.valueOf(archivedAt), Timestamp.valueOf(archivedAt), code);
    }

    // ---------------- 监测读数 ----------------

    /**
     * 写入小时均值；按“排口＋时段”唯一约束去重。
     * @return true 新写入；false 该整点读数已存在（去重）
     */
    public boolean insertHourlyReading(String code, LocalDateTime hour, BigDecimal value, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO monitor_reading(outlet_code, period_type, period_start, avg_value, "
                    + "sample_count, created_at) VALUES (?, 'HOUR', ?, ?, 1, ?)",
                    code, Timestamp.valueOf(hour), value, Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 写入日均值；按“排口＋日期”唯一约束去重。 */
    public boolean insertDailyReading(String code, LocalDateTime dayStart, BigDecimal value, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO monitor_reading(outlet_code, period_type, period_start, avg_value, "
                    + "sample_count, created_at) VALUES (?, 'DAY', ?, ?, 1, ?)",
                    code, Timestamp.valueOf(dayStart), value, Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public List<Map<String, Object>> findHourlyReadings(String code) {
        return jdbc.queryForList(
                "SELECT * FROM monitor_reading WHERE outlet_code = ? AND period_type = 'HOUR' "
                        + "ORDER BY period_start", code);
    }

    public List<Map<String, Object>> findDailyReadings(String code) {
        return jdbc.queryForList(
                "SELECT * FROM monitor_reading WHERE outlet_code = ? AND period_type = 'DAY' "
                        + "ORDER BY period_start", code);
    }

    // ---------------- 超标标记 ----------------

    public long insertMark(String code, String markType, LocalDateTime periodStart, LocalDateTime periodEnd,
                           BigDecimal limitValue, BigDecimal actualValue, int chainIndex,
                           String requestId, String status, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO exceedance_mark(outlet_code, mark_type, period_start, period_end, limit_value, "
                            + "actual_value, chain_index, request_id, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, markType);
            ps.setTimestamp(3, Timestamp.valueOf(periodStart));
            ps.setTimestamp(4, Timestamp.valueOf(periodEnd));
            ps.setBigDecimal(5, limitValue);
            ps.setBigDecimal(6, actualValue);
            ps.setInt(7, chainIndex);
            if (requestId == null) {
                ps.setNull(8, Types.VARCHAR);
            } else {
                ps.setString(8, requestId);
            }
            ps.setString(9, status);
            ps.setTimestamp(10, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Map<String, Object> findDailyMark(String code, LocalDateTime dayStart) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM exceedance_mark WHERE outlet_code = ? AND mark_type = 'DAILY_AVG' AND period_start = ?",
                code, Timestamp.valueOf(dayStart));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findHourlyMark(String code, LocalDateTime hour) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM exceedance_mark WHERE outlet_code = ? AND mark_type = 'HOURLY_AVG' AND period_start = ?",
                code, Timestamp.valueOf(hour));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findHourlyMarks(String code) {
        return jdbc.queryForList(
                "SELECT * FROM exceedance_mark WHERE outlet_code = ? AND mark_type = 'HOURLY_AVG' ORDER BY period_start",
                code);
    }

    public List<Map<String, Object>> findDailyMarks(String code) {
        return jdbc.queryForList(
                "SELECT * FROM exceedance_mark WHERE outlet_code = ? AND mark_type = 'DAILY_AVG' ORDER BY period_start",
                code);
    }

    /** 解除限排时关闭该排口全部生效中的小时超标标记。 */
    public int resolveActiveHourlyMarks(String code) {
        return jdbc.update(
                "UPDATE exceedance_mark SET status = 'RESOLVED' WHERE outlet_code = ? "
                        + "AND mark_type = 'HOURLY_AVG' AND status = 'ACTIVE'", code);
    }

    /** 复算：删除全部小时超标链标记后按读数重建。 */
    public int deleteHourlyMarks(String code) {
        return jdbc.update("DELETE FROM exceedance_mark WHERE outlet_code = ? AND mark_type = 'HOURLY_AVG'", code);
    }

    /** 复算：删除全部日均超标标记后按读数重建（幂等请求记录不受影响）。 */
    public int deleteDailyMarks(String code) {
        return jdbc.update("DELETE FROM exceedance_mark WHERE outlet_code = ? AND mark_type = 'DAILY_AVG'", code);
    }

    // ---------------- 限排令 ----------------

    /**
     * 下达限排令；active_slot 唯一约束保证同一排口同时刻仅一个执行中限排令，并发下达唯一受理。
     */
    public void insertOrder(String orderNo, String code, LocalDateTime triggerPeriodStart,
                            LocalDateTime issuedAt, LocalDateTime deadline, String activeSlot) {
        jdbc.update("INSERT INTO restriction_order(order_no, outlet_code, status, trigger_period_start, "
                        + "issued_at, confirm_deadline, active_slot, created_at, updated_at) "
                        + "VALUES (?, ?, 'PENDING_CONFIRM', ?, ?, ?, ?, ?, ?)",
                orderNo, code, Timestamp.valueOf(triggerPeriodStart), Timestamp.valueOf(issuedAt),
                Timestamp.valueOf(deadline), activeSlot, Timestamp.valueOf(issuedAt), Timestamp.valueOf(issuedAt));
    }

    public Map<String, Object> findOrder(String orderNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM restriction_order WHERE order_no = ?", orderNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查找当前占用执行槽的限排令（待确认/执行中/已升级均占槽，关闭后释放）。 */
    public Map<String, Object> findOccupyingOrder(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM restriction_order WHERE outlet_code = ? AND active_slot IS NOT NULL "
                        + "ORDER BY issued_at DESC", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findOrders(String code) {
        return jdbc.queryForList(
                "SELECT * FROM restriction_order WHERE outlet_code = ? ORDER BY issued_at", code);
    }

    public long countOrders(String code) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM restriction_order WHERE outlet_code = ?",
                Long.class, code);
        return c == null ? 0 : c;
    }

    /**
     * 确认执行：限排生效（PENDING_CONFIRM→ACTIVE）与排放总量冻结基数在同一 UPDATE/同一事务内完成，
     * 条件更新保证只有待确认令可受理，返回 0 表示状态已变化（重复确认/已升级/已关闭）。
     */
    public int confirmOrder(String orderNo, BigDecimal frozenTotalQty, LocalDateTime confirmedAt) {
        return jdbc.update("UPDATE restriction_order SET status = 'ACTIVE', frozen_total_qty = ?, "
                        + "confirmed_at = ?, active_slot = outlet_code, updated_at = ? "
                        + "WHERE order_no = ? AND status = 'PENDING_CONFIRM'",
                frozenTotalQty, Timestamp.valueOf(confirmedAt), Timestamp.valueOf(confirmedAt), orderNo);
    }

    /** 查找全部待确认令（升级巡检用）。 */
    public List<Map<String, Object>> findPendingConfirmOrders(String code) {
        return jdbc.queryForList(
                "SELECT * FROM restriction_order WHERE outlet_code = ? AND status = 'PENDING_CONFIRM' "
                        + "ORDER BY issued_at", code);
    }

    /**
     * 超时升级：PENDING_CONFIRM→ESCALATED，同时写入排放总量冻结基数（同事务），返回 0 表示无需升级。
     * 条件 confirm_deadline &lt;= now 保证“恰满 2 小时”当刻即升级，早一刻不升级。
     */
    public int escalateOrder(String orderNo, BigDecimal frozenTotalQty, LocalDateTime now) {
        return jdbc.update("UPDATE restriction_order SET status = 'ESCALATED', escalated_at = ?, "
                        + "escalate_reason = '确认执行期限届满（下达后恰满2小时未确认），自动升级并联动限产', "
                        + "frozen_total_qty = ?, active_slot = outlet_code, "
                        + "updated_at = ? WHERE order_no = ? AND status = 'PENDING_CONFIRM' AND confirm_deadline <= ?",
                Timestamp.valueOf(now), frozenTotalQty, Timestamp.valueOf(now), orderNo, Timestamp.valueOf(now));
    }

    /** 汇总截至某时刻全部小时均值作为排放总量冻结基数（合成单位：mg/L·小时累计）。 */
    public BigDecimal sumHourlyValuesUpTo(String code, LocalDateTime upTo) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(avg_value), 0) FROM monitor_reading WHERE outlet_code = ? "
                        + "AND period_type = 'HOUR' AND period_start <= ?",
                BigDecimal.class, code, Timestamp.valueOf(upTo));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 按触发时段查找限排令（复算补建用）。 */
    public Map<String, Object> findOrderByTrigger(String code, LocalDateTime triggerPeriodStart) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM restriction_order WHERE outlet_code = ? AND trigger_period_start = ?",
                code, Timestamp.valueOf(triggerPeriodStart));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 关闭限排令并释放执行槽；仅执行中/已升级令可关闭，复测信息与关闭同事务写入。 */
    public int closeOrder(String orderNo, LocalDateTime retestPeriodStart, LocalDateTime retestPeriodEnd,
                          BigDecimal retestValue, String releaseRequestId, LocalDateTime closedAt) {
        return jdbc.update("UPDATE restriction_order SET status = 'CLOSED', retest_period_start = ?, "
                        + "retest_period_end = ?, retest_value = ?, release_request_id = ?, closed_at = ?, "
                        + "active_slot = NULL, updated_at = ? WHERE order_no = ? AND status IN ('ACTIVE', 'ESCALATED')",
                Timestamp.valueOf(retestPeriodStart), Timestamp.valueOf(retestPeriodEnd), retestValue,
                releaseRequestId, Timestamp.valueOf(closedAt), Timestamp.valueOf(closedAt), orderNo);
    }

    // ---------------- 联动限产 ----------------

    public void insertProductionCut(String orderNo, String code, BigDecimal cutRatio, String reason,
                                    LocalDateTime now) {
        jdbc.update("INSERT INTO production_cut(order_no, outlet_code, cut_ratio, reason, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 'EFFECTIVE', ?)",
                orderNo, code, cutRatio, reason, Timestamp.valueOf(now));
    }

    public List<Map<String, Object>> findProductionCuts(String orderNo) {
        return jdbc.queryForList("SELECT * FROM production_cut WHERE order_no = ?", orderNo);
    }

    // ---------------- 数据缺失督办 ----------------

    /** 登记缺失区间；同排口同区间唯一，重复登记返回 false。 */
    public boolean insertGap(String code, LocalDateTime gapStart, LocalDateTime gapEnd, int gapMinutes,
                             String status, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO data_gap(outlet_code, gap_start, gap_end, gap_minutes, status, "
                    + "supervised_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    code, Timestamp.valueOf(gapStart), Timestamp.valueOf(gapEnd), gapMinutes, status,
                    "SUPERVISED".equals(status) ? Timestamp.valueOf(now) : null, Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public List<Map<String, Object>> findGaps(String code) {
        return jdbc.queryForList("SELECT * FROM data_gap WHERE outlet_code = ? ORDER BY gap_start", code);
    }

    public int deleteGaps(String code) {
        return jdbc.update("DELETE FROM data_gap WHERE outlet_code = ?", code);
    }

    // ---------------- 事件 ----------------

    public void insertEvent(String code, String eventType, LocalDateTime eventTime, boolean late, String payload) {
        jdbc.update("INSERT INTO outlet_event(outlet_code, event_type, event_time, late_flag, payload) "
                        + "VALUES (?, ?, ?, ?, ?)",
                code, eventType, Timestamp.valueOf(eventTime), late, payload);
    }

    public List<Map<String, Object>> findEvents(String code) {
        return jdbc.queryForList(
                "SELECT * FROM outlet_event WHERE outlet_code = ? ORDER BY id", code);
    }

    // ---------------- 幂等记录 ----------------

    public Map<String, Object> findIdempotent(String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM idempotent_request WHERE request_id = ?", requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 写入幂等占位记录；并发同键由主键拒绝（DuplicateKeyException），调用方据此回放或判 409。 */
    public boolean insertIdempotent(String requestId, String requestType, String fingerprint,
                                    String resultJson, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO idempotent_request(request_id, request_type, fingerprint, result_json, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    requestId, requestType, fingerprint, resultJson, Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 业务动作完成后回写首次结果（占位行在同一事务内，失败整体回滚）。 */
    public void updateIdempotentResult(String requestId, String resultJson) {
        jdbc.update("UPDATE idempotent_request SET result_json = ? WHERE request_id = ?",
                resultJson, requestId);
    }

    /**
     * 排口行锁（SELECT ... FOR UPDATE）：溢流标记写路径与日结冻结在同一事务内先取锁，
     * 保证“标记与日结竞争”时冻结到一致的双口径快照。
     */
    public void lockOutlet(String code) {
        jdbc.queryForList("SELECT outlet_code FROM discharge_outlet WHERE outlet_code = ? FOR UPDATE",
                String.class, code);
    }
}
