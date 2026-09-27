package com.example.rebate.repo;

import com.example.rebate.domain.ClawbackRecovery;
import com.example.rebate.domain.RecoveryStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 追缴明细数据访问。
 */
@Repository
public class ClawbackRecoveryRepository {

    private static final RowMapper<ClawbackRecovery> MAPPER = new RecoveryRowMapper();

    private static final String BASE_SELECT =
            "SELECT id, clawback_id, approved_amount_cents, received_amount_cents, status, "
                    + "created_at, updated_at FROM t_clawback_recovery";

    private final JdbcTemplate jdbc;

    public ClawbackRecoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 批准冲回的同一事务内产生追缴明细（已收回 0、追缴中），回填主键。
     */
    public long insert(long clawbackId, long approvedAmountCents, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_clawback_recovery
                        (clawback_id, approved_amount_cents, received_amount_cents, status,
                         created_at, updated_at)
                    VALUES (?, ?, 0, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, clawbackId);
            ps.setLong(2, approvedAmountCents);
            ps.setString(3, RecoveryStatus.RECOVERING.name());
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ClawbackRecovery> findByClawbackId(long clawbackId) {
        return jdbc.query(BASE_SELECT + " WHERE clawback_id = ?", MAPPER, clawbackId)
                .stream().findFirst();
    }

    public Optional<ClawbackRecovery> findByIdForUpdate(long id) {
        return jdbc.query(BASE_SELECT + " WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 累加一笔到账并更新状态。状态由调用方按累计是否恰等于批准额决定。
     */
    public void addReceived(long id, long additionalCents, RecoveryStatus status, Instant now) {
        jdbc.update("""
                UPDATE t_clawback_recovery
                SET received_amount_cents = received_amount_cents + ?,
                    status = ?, updated_at = ?
                WHERE id = ?
                """,
                additionalCents, status.name(), Timestamp.from(now), id);
    }

    private static final class RecoveryRowMapper implements RowMapper<ClawbackRecovery> {
        @Override
        public ClawbackRecovery mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClawbackRecovery(
                    rs.getLong("id"),
                    rs.getLong("clawback_id"),
                    rs.getLong("approved_amount_cents"),
                    rs.getLong("received_amount_cents"),
                    RecoveryStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }
    }
}
