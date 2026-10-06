package com.example.starter.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 复检记录数据访问。
 */
@Repository
public class ReinspectionRepository {

    private final JdbcTemplate jdbc;

    public ReinspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记复检记录，返回自增主键。
     */
    public long insert(long repairOrderId, String result, LocalDateTime inspectedAt, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO reinspection_record(repair_order_id, result, inspected_at, created_at)"
                            + " VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, repairOrderId);
            ps.setString(2, result);
            ps.setTimestamp(3, Timestamp.valueOf(inspectedAt));
            ps.setTimestamp(4, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
