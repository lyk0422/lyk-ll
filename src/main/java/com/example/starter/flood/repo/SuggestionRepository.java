package com.example.starter.flood.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 在途指令冲突替代建议仓储；(order_id, new_plan_id) 唯一约束防止对同一指令重复建议。
 */
@Repository
public class SuggestionRepository {

    private final JdbcTemplate jdbcTemplate;

    public SuggestionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static final RowMapper<SuggestionPO> MAPPER = new RowMapper<>() {
        @Override
        public SuggestionPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            SuggestionPO po = new SuggestionPO();
            po.id = rs.getLong("id");
            po.suggestionNo = rs.getString("suggestion_no");
            po.orderId = rs.getLong("order_id");
            po.oldPlanId = rs.getLong("old_plan_id");
            po.newPlanId = rs.getLong("new_plan_id");
            po.oldVersionNo = rs.getInt("old_version_no");
            po.newVersionNo = rs.getInt("new_version_no");
            po.suggestionDetail = rs.getString("suggestion_detail");
            po.status = rs.getString("status");
            po.decision = rs.getString("decision");
            po.confirmedBy = rs.getString("confirmed_by");
            Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
            po.confirmedAt = confirmedAt == null ? null : confirmedAt.toLocalDateTime();
            po.confirmNote = rs.getString("confirm_note");
            po.confirmRequestId = rs.getString("confirm_request_id");
            po.createdAt = rs.getTimestamp("created_at").toLocalDateTime();
            return po;
        }
    };

    /**
     * 写入替代建议；同一指令对同一新版本的重复建议被唯一约束拒绝并忽略。
     */
    public void insertIfAbsent(SuggestionPO po) {
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO order_conflict_suggestion(suggestion_no, order_id, old_plan_id, "
                                + "new_plan_id, old_version_no, new_version_no, suggestion_detail, "
                                + "status, decision, confirmed_by, confirmed_at, confirm_note, "
                                + "confirm_request_id, created_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, po.suggestionNo);
                ps.setLong(2, po.orderId);
                ps.setLong(3, po.oldPlanId);
                ps.setLong(4, po.newPlanId);
                ps.setInt(5, po.oldVersionNo);
                ps.setInt(6, po.newVersionNo);
                ps.setString(7, po.suggestionDetail);
                ps.setString(8, po.status);
                ps.setString(9, po.decision);
                ps.setString(10, po.confirmedBy);
                ps.setTimestamp(11, po.confirmedAt == null ? null
                        : Timestamp.valueOf(po.confirmedAt));
                ps.setString(12, po.confirmNote);
                ps.setString(13, po.confirmRequestId);
                ps.setTimestamp(14, Timestamp.valueOf(po.createdAt));
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key != null) {
                po.id = key.longValue();
            }
        } catch (DuplicateKeyException e) {
            // 同一指令对同一新版本已存在建议：幂等忽略
        }
    }

    public SuggestionPO findByNo(String suggestionNo) {
        List<SuggestionPO> list = jdbcTemplate.query(
                "SELECT * FROM order_conflict_suggestion WHERE suggestion_no = ?",
                MAPPER, suggestionNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public SuggestionPO findByConfirmRequestId(String confirmRequestId) {
        List<SuggestionPO> list = jdbcTemplate.query(
                "SELECT * FROM order_conflict_suggestion WHERE confirm_request_id = ?",
                MAPPER, confirmRequestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 指令的全部替代建议（留痕可查），按时间升序。
     */
    public List<SuggestionPO> findByOrderId(Long orderId) {
        return jdbcTemplate.query(
                "SELECT * FROM order_conflict_suggestion WHERE order_id = ? ORDER BY id",
                MAPPER, orderId);
    }

    /**
     * 人工确认：仅 PENDING 态可确认，返回受影响行数。
     */
    public int confirm(Long id, String decision, String confirmedBy, LocalDateTime confirmedAt,
                       String confirmNote, String confirmRequestId) {
        return jdbcTemplate.update(
                "UPDATE order_conflict_suggestion SET status = 'CONFIRMED', decision = ?, "
                        + "confirmed_by = ?, confirmed_at = ?, confirm_note = ?, "
                        + "confirm_request_id = ? WHERE id = ? AND status = 'PENDING'",
                decision, confirmedBy, Timestamp.valueOf(confirmedAt), confirmNote,
                confirmRequestId, id);
    }
}
