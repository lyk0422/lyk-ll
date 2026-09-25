package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;

/**
 * 编组核对记录数据访问。
 */
@Repository
public class ConsistCheckRepository {

    private final JdbcTemplate jdbc;

    public ConsistCheckRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一次核对结果（无论是否一致均留痕）。
     */
    public long insert(long jobId, int consistVersion,
                       int expectedCarCount, BigDecimal expectedTotalLength, BigDecimal expectedTotalWeight,
                       int actualCarCount, BigDecimal actualTotalLength, BigDecimal actualTotalWeight,
                       boolean matched, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO consist_check (job_id, consist_version, expected_car_count,"
                            + " expected_total_length, expected_total_weight, actual_car_count,"
                            + " actual_total_length, actual_total_weight, matched, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, jobId);
            ps.setInt(2, consistVersion);
            ps.setInt(3, expectedCarCount);
            ps.setBigDecimal(4, expectedTotalLength);
            ps.setBigDecimal(5, expectedTotalWeight);
            ps.setInt(6, actualCarCount);
            ps.setBigDecimal(7, actualTotalLength);
            ps.setBigDecimal(8, actualTotalWeight);
            ps.setBoolean(9, matched);
            ps.setString(10, requestId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
