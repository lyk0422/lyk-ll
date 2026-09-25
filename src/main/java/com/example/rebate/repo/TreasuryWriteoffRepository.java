package com.example.rebate.repo;

import com.example.rebate.domain.TreasuryWriteoff;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 退库核销登记数据访问。批次＋国库流水号唯一由数据库约束保证。
 */
@Repository
public class TreasuryWriteoffRepository {

    private static final RowMapper<TreasuryWriteoff> MAPPER = new WriteoffRowMapper();

    private final JdbcTemplate jdbc;

    public TreasuryWriteoffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记一条核销记录。
     */
    public void insert(TreasuryWriteoff w) {
        jdbc.update("""
                INSERT INTO t_treasury_writeoff
                    (batch_id, treasury_serial, declared_tax, received_amount, diff_amount,
                     diff_ratio, consistent, manual_flag, registered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                w.batchId(), w.treasurySerial(), w.declaredTax(), w.receivedAmount(),
                w.diffAmount(), w.diffRatio(), w.consistent() ? "1" : "0",
                w.manualFlag() ? "1" : "0", java.sql.Timestamp.from(w.registeredAt()));
    }

    /**
     * 按批次＋国库流水号查询（幂等重放用）。
     */
    public List<TreasuryWriteoff> find(long batchId, String treasurySerial) {
        return jdbc.query(BASE_SELECT + " WHERE batch_id = ? AND treasury_serial = ?",
                MAPPER, batchId, treasurySerial);
    }

    /**
     * 查询批次全部核销记录（复算用）。
     */
    public List<TreasuryWriteoff> findByBatchId(long batchId) {
        return jdbc.query(BASE_SELECT + " WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    private static final String BASE_SELECT =
            "SELECT id, batch_id, treasury_serial, declared_tax, received_amount, diff_amount, "
                    + "diff_ratio, consistent, manual_flag, registered_at FROM t_treasury_writeoff";

    private static final class WriteoffRowMapper implements RowMapper<TreasuryWriteoff> {
        @Override
        public TreasuryWriteoff mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TreasuryWriteoff(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getString("treasury_serial"),
                    rs.getBigDecimal("declared_tax"),
                    rs.getBigDecimal("received_amount"),
                    rs.getBigDecimal("diff_amount").setScale(4, java.math.RoundingMode.HALF_UP),
                    rs.getBigDecimal("diff_ratio").setScale(8, java.math.RoundingMode.HALF_UP),
                    "1".equals(rs.getString("consistent")),
                    "1".equals(rs.getString("manual_flag")),
                    rs.getTimestamp("registered_at").toInstant());
        }
    }
}
