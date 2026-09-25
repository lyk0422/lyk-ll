package com.example.starter.cems.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.cems.web.dto.OrderView;

/**
 * 限排令仓储。执行中限排令以 active_slot=排口编码 + 唯一索引保证同排口同时刻唯一受理。
 */
@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入执行中限排令；同排口已存在执行中限排令时由 uk_order_active 抛出 DuplicateKeyException。返回自增主键。 */
    public long insert(OrderView v) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO restriction_order(outfall_code, request_id, active_slot, trigger_slots, "
                            + "status, issued_at, confirm_deadline, confirmed, confirmed_at, escalated, "
                            + "escalated_at, production_limited, baseline_total, baseline_frozen_at, "
                            + "retest_slot, retest_value, release_request_id, released_at, created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, v.outfallCode());
            ps.setString(2, v.requestId());
            ps.setString(3, v.outfallCode());
            ps.setString(4, v.triggerSlots());
            ps.setString(5, v.status());
            ps.setTimestamp(6, ts(v.issuedAt()));
            ps.setTimestamp(7, ts(v.confirmDeadline()));
            ps.setInt(8, v.confirmed() ? 1 : 0);
            ps.setTimestamp(9, ts(v.confirmedAt()));
            ps.setInt(10, v.escalated() ? 1 : 0);
            ps.setTimestamp(11, ts(v.escalatedAt()));
            ps.setInt(12, v.productionLimited() ? 1 : 0);
            ps.setBigDecimal(13, v.baselineTotal());
            ps.setTimestamp(14, ts(v.baselineFrozenAt()));
            ps.setTimestamp(15, ts(v.retestSlot()));
            ps.setBigDecimal(16, v.retestValue());
            ps.setString(17, v.releaseRequestId());
            ps.setTimestamp(18, ts(v.releasedAt()));
            ps.setTimestamp(19, ts(v.issuedAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按 requestId 查询。 */
    public OrderView findByRequestId(String requestId) {
        var list = jdbc.query("SELECT * FROM restriction_order WHERE request_id = ?",
                MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询某排口当前执行中（未关闭）限排令。 */
    public OrderView findActiveByCode(String outfallCode) {
        var list = jdbc.query(
                "SELECT * FROM restriction_order WHERE active_slot = ?",
                MAPPER, outfallCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 全部未关闭限排令（超时升级扫描用）。 */
    public List<OrderView> findAllOpen() {
        return jdbc.query(
                "SELECT * FROM restriction_order WHERE active_slot IS NOT NULL ORDER BY issued_at, id",
                MAPPER);
    }

    /** 列出某排口全部限排令。 */
    public List<OrderView> findAllByCode(String outfallCode) {
        return jdbc.query(
                "SELECT * FROM restriction_order WHERE outfall_code = ? ORDER BY issued_at, id",
                MAPPER, outfallCode);
    }

    /**
     * 2 小时内确认执行：仅当尚未确认/升级且当前时刻严格早于截止时刻时成功；
     * 恰满 2 小时（now == deadline）不在“2 小时内”，返回 0，由调用方走升级。
     */
    public int confirmIfWithinDeadline(long id, LocalDateTime confirmedAt) {
        return jdbc.update(
                "UPDATE restriction_order SET confirmed = 1, confirmed_at = ? "
                        + "WHERE id = ? AND confirmed = 0 AND escalated = 0 AND ? < confirm_deadline",
                ts(confirmedAt), id, ts(confirmedAt));
    }

    /**
     * 恰满 2 小时未确认：升级并联动限产。仅在 now &gt;= confirm_deadline 且未确认、未升级时生效，
     * 保证“恰满 2 小时”即升级（截止时刻是下达后整点 2 小时）。
     */
    public int escalateIfDeadlineReached(long id, LocalDateTime now) {
        return jdbc.update(
                "UPDATE restriction_order SET status = 'ESCALATED', escalated = 1, escalated_at = ?, "
                        + "production_limited = 1 WHERE id = ? AND active_slot IS NOT NULL "
                        + "AND confirmed = 0 AND escalated = 0 AND ? >= confirm_deadline",
                ts(now), id, ts(now));
    }

    /** 复测合格：记录复测结果并关闭限排令（active_slot 释放为 NULL）。 */
    public int close(long id, LocalDateTime retestSlot, BigDecimal retestValue,
                     String releaseRequestId, LocalDateTime releasedAt) {
        return jdbc.update(
                "UPDATE restriction_order SET status = 'CLOSED', active_slot = NULL, "
                        + "retest_slot = ?, retest_value = ?, release_request_id = ?, released_at = ? "
                        + "WHERE id = ? AND active_slot IS NOT NULL",
                ts(retestSlot), retestValue, releaseRequestId, ts(releasedAt), id);
    }

    /** 测试清理。 */
    public void deleteAll() {
        jdbc.update("DELETE FROM restriction_order");
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }

    private static final RowMapper<OrderView> MAPPER = new RowMapper<>() {
        @Override
        public OrderView mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new OrderView(
                    rs.getLong("id"),
                    rs.getString("outfall_code"),
                    rs.getString("request_id"),
                    rs.getString("trigger_slots"),
                    rs.getString("status"),
                    rs.getTimestamp("issued_at").toLocalDateTime(),
                    rs.getTimestamp("confirm_deadline").toLocalDateTime(),
                    rs.getInt("confirmed") == 1,
                    rs.getTimestamp("confirmed_at") == null ? null
                            : rs.getTimestamp("confirmed_at").toLocalDateTime(),
                    rs.getInt("escalated") == 1,
                    rs.getTimestamp("escalated_at") == null ? null
                            : rs.getTimestamp("escalated_at").toLocalDateTime(),
                    rs.getInt("production_limited") == 1,
                    rs.getBigDecimal("baseline_total"),
                    rs.getTimestamp("baseline_frozen_at").toLocalDateTime(),
                    rs.getTimestamp("retest_slot") == null ? null
                            : rs.getTimestamp("retest_slot").toLocalDateTime(),
                    rs.getBigDecimal("retest_value"),
                    rs.getString("release_request_id"),
                    rs.getTimestamp("released_at") == null ? null
                            : rs.getTimestamp("released_at").toLocalDateTime());
        }
    };
}
