package com.example.starter.payment.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 日切批次仓储：负责批次行的创建、悲观行锁与 OPEN→CLOSED 状态翻转。
 */
@Repository
public class BatchRepository {

    public record BatchRow(String batchDate, String status, Instant closedAt) {
    }

    private static final RowMapper<BatchRow> MAPPER = (rs, n) -> {
        Timestamp closed = rs.getTimestamp("closed_at");
        return new BatchRow(rs.getString("batch_date"), rs.getString("status"),
                closed == null ? null : closed.toInstant());
    };

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 若批次不存在则创建为 OPEN；并发下只产生一行。
     * MERGE 仅带主键列：行已存在时只回写同值主键，不触碰 status，故不会把 CLOSED 改回 OPEN。
     */
    public void ensureBatch(String batchDate) {
        jdbc.update("MERGE INTO payment_batch (batch_date) KEY(batch_date) VALUES (?)", batchDate);
    }

    public Optional<BatchRow> find(String batchDate) {
        return jdbc.query("SELECT batch_date, status, closed_at FROM payment_batch WHERE batch_date = ?",
                MAPPER, batchDate).stream().findFirst();
    }

    /**
     * 在当前事务内对批次行加悲观写锁（FOR UPDATE），串行化同批次的入账/封账竞态。
     * 调用前须已 {@link #ensureBatch}。
     */
    public BatchRow lock(String batchDate) {
        return jdbc.queryForObject(
                "SELECT batch_date, status, closed_at FROM payment_batch WHERE batch_date = ? FOR UPDATE",
                MAPPER, batchDate);
    }

    /**
     * 仅当批次仍为 OPEN 时翻转为 CLOSED。
     *
     * @return 受影响行数；1 表示本次封账成功，0 表示已被并发封账
     */
    public int closeIfOpen(String batchDate, Instant closedAt) {
        return jdbc.update("UPDATE payment_batch SET status = 'CLOSED', closed_at = ? "
                + "WHERE batch_date = ? AND status = 'OPEN'", Timestamp.from(closedAt), batchDate);
    }
}
