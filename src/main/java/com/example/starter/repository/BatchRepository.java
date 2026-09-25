package com.example.starter.repository;

import com.example.starter.entity.SettlementBatch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 日切批次表数据访问。 */
@Repository
public class BatchRepository {

    private static final RowMapper<SettlementBatch> MAPPER = new BatchRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public BatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SettlementBatch findByDate(LocalDate batchDate) {
        List<SettlementBatch> list = jdbcTemplate.query(
                "SELECT batch_date, status, closed_at, merchant_count, total_amount, total_fee, settled_amount, created_at "
                        + "FROM settlement_batch WHERE batch_date = ?",
                MAPPER, batchDate);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 不存在则创建开放批次，已存在则忽略。 */
    public void insertIfOpenAbsent(LocalDate batchDate, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO settlement_batch (batch_date, status, created_at) "
                        + "SELECT ?, 'OPEN', ? WHERE NOT EXISTS (SELECT 1 FROM settlement_batch WHERE batch_date = ?)",
                batchDate, Timestamp.from(now), batchDate);
    }

    /** 行级锁读取批次（必须在事务内），用于封账/入账竞态串行化。 */
    public SettlementBatch findByDateForUpdate(LocalDate batchDate) {
        List<SettlementBatch> list = jdbcTemplate.query(
                "SELECT batch_date, status, closed_at, merchant_count, total_amount, total_fee, settled_amount, created_at "
                        + "FROM settlement_batch WHERE batch_date = ? FOR UPDATE",
                MAPPER, batchDate);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 条件置为封账终态；返回受影响行数，0 表示已被其他事务封账。 */
    public int markClosed(LocalDate batchDate, Instant closedAt, int merchantCount,
                          long totalAmount, long totalFee, long settledAmount) {
        return jdbcTemplate.update(
                "UPDATE settlement_batch SET status = 'CLOSED', closed_at = ?, merchant_count = ?, "
                        + "total_amount = ?, total_fee = ?, settled_amount = ? "
                        + "WHERE batch_date = ? AND status = 'OPEN'",
                Timestamp.from(closedAt), merchantCount, totalAmount, totalFee, settledAmount, batchDate);
    }

    private static class BatchRowMapper implements RowMapper<SettlementBatch> {
        @Override
        public SettlementBatch mapRow(ResultSet rs, int rowNum) throws SQLException {
            SettlementBatch b = new SettlementBatch();
            b.setBatchDate(rs.getDate("batch_date").toLocalDate());
            b.setStatus(rs.getString("status"));
            Timestamp closedAt = rs.getTimestamp("closed_at");
            b.setClosedAt(closedAt == null ? null : closedAt.toInstant());
            b.setMerchantCount(rs.getInt("merchant_count"));
            b.setTotalAmount(rs.getLong("total_amount"));
            b.setTotalFee(rs.getLong("total_fee"));
            b.setSettledAmount(rs.getLong("settled_amount"));
            b.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            return b;
        }
    }
}
