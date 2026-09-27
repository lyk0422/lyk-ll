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
import java.util.Objects;

/**
 * 巡检检查项数据访问：plan_check_item 表。紧急中止不删除已保存检查项。
 */
@Repository
public class PlanCheckItemRepository {

    private static final RowMapper<PlanCheckItemRecord> MAPPER = (rs, rowNum) -> new PlanCheckItemRecord(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("item_code"),
            CheckResult.valueOf(rs.getString("result")),
            rs.getString("remark"),
            rs.getTimestamp("observed_at").toInstant(),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public PlanCheckItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PlanCheckItemRecord insert(PlanCheckItemRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO plan_check_item (plan_id, item_code, result, remark, observed_at, request_id,"
                            + " created_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, record.planId());
            ps.setString(2, record.itemCode());
            ps.setString(3, record.result().name());
            ps.setString(4, record.remark());
            ps.setTimestamp(5, Timestamp.from(record.observedAt()));
            ps.setString(6, record.requestId());
            ps.setTimestamp(7, Timestamp.from(record.createdAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return new PlanCheckItemRecord(id, record.planId(), record.itemCode(), record.result(), record.remark(),
                record.observedAt(), record.requestId(), record.createdAt());
    }

    public List<PlanCheckItemRecord> findByPlanId(long planId) {
        return jdbc.query("SELECT * FROM plan_check_item WHERE plan_id = ? ORDER BY observed_at, id",
                MAPPER, planId);
    }
}
