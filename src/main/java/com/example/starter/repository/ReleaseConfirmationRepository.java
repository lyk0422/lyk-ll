package com.example.starter.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 限功率解除双人确认记录数据访问。
 */
@Repository
public class ReleaseConfirmationRepository {

    private final JdbcTemplate jdbc;

    public ReleaseConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long directiveId, long retestId, String operationStaffId,
            String monitoringStaffId, Instant releasedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_confirmation (directive_id, retest_id, operation_staff_id,"
                            + " monitoring_staff_id, released_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, directiveId);
            ps.setLong(2, retestId);
            ps.setString(3, operationStaffId);
            ps.setString(4, monitoringStaffId);
            ps.setTimestamp(5, Timestamp.from(releasedAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public int countByDirective(long directiveId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_confirmation WHERE directive_id = ?",
                Integer.class, directiveId);
        return count == null ? 0 : count;
    }
}
