package com.example.starter.atm.repo;

import com.example.starter.atm.domain.OverShort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 长短款挂账链数据访问。plan_id 唯一：一单最多一笔挂账；挂账与核销在同一事务落库。
 */
@Repository
public class OverShortRepository {

    private static final RowMapper<OverShort> MAPPER = (rs, n) -> new OverShort(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("direction"),
            rs.getLong("amount"),
            rs.getLong("planned_amount"),
            rs.getLong("actual_amount"),
            rs.getString("status"),
            rs.getLong("occurred_at"),
            rs.getString("deadline_utc_date"),
            getNullableLong(rs, "settled_at"),
            rs.getString("settle_request_id"),
            getNullableLong(rs, "escalated_at"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static Long getNullableLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private final JdbcTemplate jdbc;

    public OverShortRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(OverShort o) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO over_short (plan_id, direction, amount, planned_amount, actual_amount, "
                            + "status, occurred_at, deadline_utc_date, settled_at, settle_request_id, "
                            + "escalated_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, o.planId());
            ps.setString(2, o.direction());
            ps.setLong(3, o.amount());
            ps.setLong(4, o.plannedAmount());
            ps.setLong(5, o.actualAmount());
            ps.setString(6, o.status());
            ps.setLong(7, o.occurredAt());
            ps.setString(8, o.deadlineUtcDate());
            setNullable(ps, 9, o.settledAt());
            ps.setString(10, o.settleRequestId());
            setNullable(ps, 11, o.escalatedAt());
            ps.setLong(12, o.createdAt());
            ps.setLong(13, o.updatedAt());
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private static void setNullable(PreparedStatement ps, int idx, Long v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.BIGINT);
        } else {
            ps.setLong(idx, v);
        }
    }

    public OverShort findById(long id) {
        List<OverShort> list = jdbc.query("SELECT * FROM over_short WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public OverShort findByPlanId(long planId) {
        List<OverShort> list = jdbc.query("SELECT * FROM over_short WHERE plan_id = ?", MAPPER, planId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 销账受理：仅 OPEN 且未过截止 UTC 日时生效。 */
    public int markSettled(long id, String settleRequestId, long settledAt) {
        return jdbc.update(
                "UPDATE over_short SET status = 'SETTLED', settled_at = ?, settle_request_id = ?, "
                        + "updated_at = ? WHERE id = ? AND status = 'OPEN'",
                settledAt, settleRequestId, settledAt, id);
    }

    /** 逾期升级：仅 OPEN 可升级。 */
    public int markEscalated(long id, long escalatedAt) {
        return jdbc.update(
                "UPDATE over_short SET status = 'ESCALATED', escalated_at = ?, updated_at = ? "
                        + "WHERE id = ? AND status = 'OPEN'",
                escalatedAt, escalatedAt, id);
    }

    public OverShort findBySettleRequestId(String requestId) {
        List<OverShort> list = jdbc.query(
                "SELECT * FROM over_short WHERE settle_request_id = ?", MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<OverShort> findAll() {
        return jdbc.query("SELECT * FROM over_short ORDER BY id", MAPPER);
    }
}
