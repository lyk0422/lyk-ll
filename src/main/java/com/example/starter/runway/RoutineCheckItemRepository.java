package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 例行巡检检查项数据访问：routine_check_item 表。
 */
@Repository
public class RoutineCheckItemRepository {

    private static final RowMapper<RoutineCheckItem> MAPPER = (rs, rowNum) -> new RoutineCheckItem(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("item_code"),
            rs.getString("item_text"),
            rs.getBoolean("passed"),
            rs.getTimestamp("completed_at").toInstant(),
            rs.getInt("late") != 0,
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public RoutineCheckItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RoutineCheckItem insert(RoutineCheckItem item) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO routine_check_item (plan_id, item_code, item_text, passed, completed_at,"
                            + " late, request_id, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, item.planId());
            ps.setString(2, item.itemCode());
            ps.setString(3, item.itemText());
            ps.setBoolean(4, item.passed());
            ps.setTimestamp(5, Timestamp.from(item.completedAt()));
            ps.setInt(6, item.late() ? 1 : 0);
            ps.setString(7, item.requestId());
            ps.setTimestamp(8, Timestamp.from(item.createdAt()));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey() == null ? 0L : keyHolder.getKey().longValue();
        return new RoutineCheckItem(id, item.planId(), item.itemCode(), item.itemText(), item.passed(),
                item.completedAt(), item.late(), item.requestId(), item.createdAt());
    }

    public List<RoutineCheckItem> findByPlanId(long planId) {
        return jdbc.query("SELECT * FROM routine_check_item WHERE plan_id = ? ORDER BY id", MAPPER, planId);
    }
}
