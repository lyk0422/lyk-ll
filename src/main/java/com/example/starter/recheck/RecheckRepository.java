package com.example.starter.recheck;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 复检申请数据访问：case_id 唯一约束保证同一案件仅受理一次复检。
 */
@Repository
public class RecheckRepository {

    public record RecheckRow(long id, String requestId, String fingerprint, long penaltyId, long caseId,
            int staticWeightKg, Instant appliedAt, String outcome) {
    }

    private static final RowMapper<RecheckRow> MAPPER = (rs, n) -> new RecheckRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getLong("penalty_id"),
            rs.getLong("case_id"),
            rs.getInt("static_weight_kg"),
            rs.getTimestamp("applied_at").toInstant(),
            rs.getString("outcome"));

    private final JdbcTemplate jdbc;

    public RecheckRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RecheckRow> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM recheck_request WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public Optional<RecheckRow> findByCaseId(long caseId) {
        return jdbc.query("SELECT * FROM recheck_request WHERE case_id = ?", MAPPER, caseId)
                .stream().findFirst();
    }

    public long insert(String requestId, String fingerprint, long penaltyId, long caseId,
            int staticWeightKg, Instant appliedAt, String outcome) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO recheck_request (request_id, fingerprint, penalty_id, case_id,"
                            + " static_weight_kg, applied_at, outcome, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, penaltyId);
            ps.setLong(4, caseId);
            ps.setInt(5, staticWeightKg);
            ps.setTimestamp(6, Timestamp.from(appliedAt));
            ps.setString(7, outcome);
            ps.setTimestamp(8, Timestamp.from(Instant.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
