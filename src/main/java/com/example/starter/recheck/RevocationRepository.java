package com.example.starter.recheck;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 撤销文书数据访问：记录退还罚款与记分，penalty_id 唯一保证一次撤销只出一份文书。
 */
@Repository
public class RevocationRepository {

    private final JdbcTemplate jdbc;

    public RevocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long penaltyId, long recheckId, BigDecimal refundedFine, int refundedPoints) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO revocation_document (penalty_id, recheck_id, refunded_fine, refunded_points, created_at)"
                            + " VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, penaltyId);
            ps.setLong(2, recheckId);
            ps.setBigDecimal(3, refundedFine);
            ps.setInt(4, refundedPoints);
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Long> findIdByPenaltyId(long penaltyId) {
        return jdbc.query("SELECT id FROM revocation_document WHERE penalty_id = ?",
                (rs, n) -> rs.getLong(1), penaltyId).stream().findFirst();
    }
}
