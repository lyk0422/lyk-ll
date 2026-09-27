package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 例行巡检检查项数据访问：routine_check_item 表。
 */
@Repository
public class RoutineCheckItemRepository {

    private static final RowMapper<RoutineCheckItem> MAPPER = (rs, rowNum) -> new RoutineCheckItem(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("item_code"),
            rs.getInt("seq_no"),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getBoolean("recorded_late"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public RoutineCheckItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RoutineCheckItem insert(RoutineCheckItem item) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO routine_check_item (plan_id, item_code, seq_no, completed_at,"
                            + " recorded_late, request_id, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, item.planId());
            ps.setString(2, item.itemCode());
            ps.setInt(3, item.seqNo());
            ps.setTimestamp(4, item.completedAt() == null ? null : Timestamp.from(item.completedAt()));
            ps.setBoolean(5, item.recordedLate());
            if (item.requestId() == null) {
                ps.setNull(6, Types.VARCHAR);
            } else {
                ps.setString(6, item.requestId());
            }
            ps.setTimestamp(7, Timestamp.from(item.createdAt()));
            ps.setTimestamp(8, Timestamp.from(item.updatedAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return findById(id).orElseThrow();
    }

    public Optional<RoutineCheckItem> findById(long id) {
        List<RoutineCheckItem> rows = jdbc.query("SELECT * FROM routine_check_item WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<RoutineCheckItem> findByPlanAndCode(long planId, String itemCode) {
        List<RoutineCheckItem> rows = jdbc.query(
                "SELECT * FROM routine_check_item WHERE plan_id = ? AND item_code = ?", MAPPER, planId, itemCode);
        return rows.stream().findFirst();
    }

    public List<RoutineCheckItem> findByPlanId(long planId) {
        return jdbc.query("SELECT * FROM routine_check_item WHERE plan_id = ? ORDER BY seq_no, id",
                MAPPER, planId);
    }

    /**
     * 检查项回报：仅当尚未回报（completed_at IS NULL）时写入，返回受影响行数，保证同一项只接受一次正常回报。
     */
    public int markCompleted(long id, Instant completedAt, boolean recordedLate, String requestId,
                             Instant updatedAt) {
        return jdbc.update("UPDATE routine_check_item SET completed_at = ?, recorded_late = ?, request_id = ?,"
                + " updated_at = ? WHERE id = ? AND completed_at IS NULL",
                Timestamp.from(completedAt), recordedLate, requestId, Timestamp.from(updatedAt), id);
    }
}
