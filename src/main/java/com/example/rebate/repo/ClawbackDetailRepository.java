package com.example.rebate.repo;

import com.example.rebate.domain.ClawbackDetail;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 追缴明细数据访问：一张已批准冲回单恰好一条，批准同事务写入。
 */
@Repository
public class ClawbackDetailRepository {

    private static final RowMapper<ClawbackDetail> MAPPER = new DetailRowMapper();

    private final JdbcTemplate jdbc;

    public ClawbackDetailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入追缴明细（独立流水号）。reversal_id 与流水号均有唯一约束兜底。
     */
    public void insert(long reversalId, String clawbackSerial, long amountCents, Instant now) {
        jdbc.update("""
                INSERT INTO t_clawback_detail
                    (reversal_id, clawback_serial, amount_cents, approved_at)
                VALUES (?, ?, ?, ?)
                """,
                reversalId, clawbackSerial, amountCents, Timestamp.from(now));
    }

    public Optional<ClawbackDetail> findByReversalId(long reversalId) {
        return jdbc.query(
                "SELECT id, reversal_id, clawback_serial, amount_cents, approved_at "
                        + "FROM t_clawback_detail WHERE reversal_id = ?",
                MAPPER, reversalId).stream().findFirst();
    }

    private static final class DetailRowMapper implements RowMapper<ClawbackDetail> {
        @Override
        public ClawbackDetail mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClawbackDetail(
                    rs.getLong("id"),
                    rs.getLong("reversal_id"),
                    rs.getString("clawback_serial"),
                    rs.getLong("amount_cents"),
                    rs.getTimestamp("approved_at").toInstant());
        }
    }
}
