package com.example.starter.penalty;

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
 * 处罚决定数据访问。
 */
@Repository
public class PenaltyRepository {

    private static final RowMapper<PenaltyDecision> MAPPER = PenaltyRepository::mapRow;

    private final JdbcTemplate jdbc;

    public PenaltyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PenaltyDecision> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM penalty_decision WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public Optional<PenaltyDecision> findById(long id) {
        return jdbc.query("SELECT * FROM penalty_decision WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public long insert(PenaltyDecision d, String fingerprint) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO penalty_decision (request_id, fingerprint, case_id, weigh_record_id, plate_no,"
                            + " over_rate, measure, fine_amount, demerit_points, status, served_at, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, d.requestId());
            ps.setString(2, fingerprint);
            ps.setLong(3, d.caseId());
            ps.setLong(4, d.weighRecordId());
            ps.setString(5, d.plateNo());
            ps.setBigDecimal(6, d.overRate());
            ps.setString(7, d.measure());
            ps.setBigDecimal(8, d.fineAmount());
            ps.setInt(9, d.demeritPoints());
            ps.setString(10, d.status());
            ps.setTimestamp(11, Timestamp.from(d.servedAt()));
            ps.setTimestamp(12, Timestamp.from(Instant.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 撤销处罚决定：仅生效中可撤销，返回更新行数。
     */
    public int revokeIfActive(long penaltyId) {
        return jdbc.update("UPDATE penalty_decision SET status = 'REVOKED' WHERE id = ? AND status = 'ACTIVE'",
                penaltyId);
    }

    private static PenaltyDecision mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PenaltyDecision(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("case_id"),
                rs.getLong("weigh_record_id"),
                rs.getString("plate_no"),
                rs.getBigDecimal("over_rate"),
                rs.getString("measure"),
                rs.getBigDecimal("fine_amount"),
                rs.getInt("demerit_points"),
                rs.getString("status"),
                rs.getTimestamp("served_at").toInstant());
    }
}
