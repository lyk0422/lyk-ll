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
 * 复测记录数据访问。
 */
@Repository
public class RetestRepository {

    private final JdbcTemplate jdbc;

    public RetestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long transmitterId, String result, Instant sampledAt, String evidenceRef,
            Instant recordedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO retest_record (transmitter_id, result, sampled_at, evidence_ref, recorded_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, transmitterId);
            ps.setString(2, result);
            ps.setTimestamp(3, Timestamp.from(sampledAt));
            ps.setString(4, evidenceRef);
            ps.setTimestamp(5, Timestamp.from(recordedAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public int countByTransmitter(long transmitterId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM retest_record WHERE transmitter_id = ?",
                Integer.class, transmitterId);
        return count == null ? 0 : count;
    }
}
