package com.example.starter.flood.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 在途指令与新方案版本冲突的替代建议仓储；确认留痕可查。
 */
@Repository
public class AdviceRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdviceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static final RowMapper<AdvicePO> MAPPER = new RowMapper<>() {
        @Override
        public AdvicePO mapRow(ResultSet rs, int rowNum) throws SQLException {
            AdvicePO po = new AdvicePO();
            po.id = rs.getLong("id");
            po.adviceNo = rs.getString("advice_no");
            po.orderId = rs.getLong("order_id");
            po.newPlanId = rs.getLong("new_plan_id");
            po.suggestion = rs.getString("suggestion");
            po.status = rs.getString("status");
            po.requestId = rs.getString("request_id");
            po.decision = rs.getString("decision");
            po.confirmNote = rs.getString("confirm_note");
            po.confirmedBy = rs.getString("confirmed_by");
            po.createdAt = toLdt(rs.getTimestamp("created_at"));
            po.confirmedAt = toLdt(rs.getTimestamp("confirmed_at"));
            return po;
        }
    };

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public void insert(AdvicePO po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO order_conflict_advice(advice_no, order_id, new_plan_id, suggestion, "
                            + "status, request_id, decision, confirm_note, confirmed_by, "
                            + "created_at, confirmed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.adviceNo);
            ps.setLong(2, po.orderId);
            ps.setLong(3, po.newPlanId);
            ps.setString(4, po.suggestion);
            ps.setString(5, po.status);
            ps.setString(6, po.requestId);
            ps.setString(7, po.decision);
            ps.setString(8, po.confirmNote);
            ps.setString(9, po.confirmedBy);
            ps.setTimestamp(10, Timestamp.valueOf(po.createdAt));
            ps.setTimestamp(11, po.confirmedAt == null ? null : Timestamp.valueOf(po.confirmedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            po.id = key.longValue();
        }
    }

    public AdvicePO findByNo(String adviceNo) {
        List<AdvicePO> list = jdbcTemplate.query(
                "SELECT * FROM order_conflict_advice WHERE advice_no = ?", MAPPER, adviceNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public AdvicePO findByOrderId(Long orderId) {
        List<AdvicePO> list = jdbcTemplate.query(
                "SELECT * FROM order_conflict_advice WHERE order_id = ?", MAPPER, orderId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 人工确认：仅 PENDING 建议可确认，返回受影响行数（并发下恰一个确认成功）。
     */
    public int confirm(Long adviceId, String decision, String confirmNote, String confirmedBy,
                       LocalDateTime confirmedAt) {
        return jdbcTemplate.update(
                "UPDATE order_conflict_advice SET status = 'CONFIRMED', decision = ?, "
                        + "confirm_note = ?, confirmed_by = ?, confirmed_at = ? "
                        + "WHERE id = ? AND status = 'PENDING'",
                decision, confirmNote, confirmedBy, Timestamp.valueOf(confirmedAt), adviceId);
    }
}
