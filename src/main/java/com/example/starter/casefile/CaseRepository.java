package com.example.starter.casefile;

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
 * 案件数据访问。
 */
@Repository
public class CaseRepository {

    private static final RowMapper<CaseRecord> MAPPER = CaseRepository::mapRow;

    private final JdbcTemplate jdbc;

    public CaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertOpen(String plateNo) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO case_record (penalty_id, plate_no, status, closed_at, created_at)"
                            + " VALUES (NULL, ?, 'OPEN', NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, plateNo);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 同事务内回填案件关联的处罚决定编号。
     */
    public void assignPenalty(long caseId, long penaltyId) {
        jdbc.update("UPDATE case_record SET penalty_id = ? WHERE id = ?", penaltyId, caseId);
    }

    public Optional<CaseRecord> findById(long id) {
        return jdbc.query("SELECT * FROM case_record WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<CaseRecord> findByPenaltyId(long penaltyId) {
        return jdbc.query("SELECT * FROM case_record WHERE penalty_id = ?", MAPPER, penaltyId)
                .stream().findFirst();
    }

    /**
     * 查某车牌在指定过磅时刻已结案的案件（结案时间不早于过磅时刻），用于迟到数据判定。
     */
    public Optional<CaseRecord> findClosedCaseFor(String plateNo, Instant weighedAt) {
        return jdbc.query(
                "SELECT * FROM case_record WHERE plate_no = ? AND status = 'CLOSED' AND closed_at >= ?"
                        + " ORDER BY closed_at DESC LIMIT 1",
                MAPPER, plateNo, Timestamp.from(weighedAt)).stream().findFirst();
    }

    /**
     * 结案：仅 OPEN 状态可转为 CLOSED，返回更新行数（0 表示已结案，幂等）。
     */
    public int closeIfOpen(long caseId, Instant closedAt) {
        return jdbc.update("UPDATE case_record SET status = 'CLOSED', closed_at = ?"
                        + " WHERE id = ? AND status = 'OPEN'",
                Timestamp.from(closedAt), caseId);
    }

    private static CaseRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new CaseRecord(
                rs.getLong("id"),
                rs.getLong("penalty_id"),
                rs.getString("plate_no"),
                rs.getString("status"),
                closedAt == null ? null : closedAt.toInstant());
    }
}
