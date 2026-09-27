package com.example.rebate.repo;

import com.example.rebate.domain.ClawbackReceipt;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Statement;

/**
 * 追缴回执数据访问。流水号全局唯一，同流水重复登记以唯一约束兜底幂等校验。
 */
@Repository
public class ClawbackReceiptRepository {

    private static final RowMapper<ClawbackReceipt> MAPPER = new ReceiptRowMapper();

    private static final String BASE_SELECT =
            "SELECT id, recovery_id, clawback_id, serial_no, received_amount_cents, received_at, "
                    + "created_at FROM t_clawback_receipt";

    private final JdbcTemplate jdbc;

    public ClawbackReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条回执。流水号唯一冲突抛 {@link DuplicateKeyException}，由服务层区分重放/异金额。
     */
    @Transactional
    public long insert(long recoveryId, long clawbackId, String serialNo,
                       long receivedAmountCents, Instant receivedAt, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_clawback_receipt
                        (recovery_id, clawback_id, serial_no, received_amount_cents, received_at,
                         created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, recoveryId);
            ps.setLong(2, clawbackId);
            ps.setString(3, serialNo);
            ps.setLong(4, receivedAmountCents);
            ps.setTimestamp(5, Timestamp.from(receivedAt));
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ClawbackReceipt> findBySerial(String serialNo) {
        return jdbc.query(BASE_SELECT + " WHERE serial_no = ?", MAPPER, serialNo)
                .stream().findFirst();
    }

    public List<ClawbackReceipt> findByRecoveryId(long recoveryId) {
        return jdbc.query(
                BASE_SELECT + " WHERE recovery_id = ? ORDER BY id", MAPPER, recoveryId);
    }

    private static final class ReceiptRowMapper implements RowMapper<ClawbackReceipt> {
        @Override
        public ClawbackReceipt mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClawbackReceipt(
                    rs.getLong("id"),
                    rs.getLong("recovery_id"),
                    rs.getLong("clawback_id"),
                    rs.getString("serial_no"),
                    rs.getLong("received_amount_cents"),
                    rs.getTimestamp("received_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
