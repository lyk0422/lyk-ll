package com.example.starter.payment.repo;

import com.example.starter.payment.PayBatch;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 日切批次数据访问。封账与入账通过行级锁（SELECT ... FOR UPDATE）串行化。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<PayBatch> MAPPER = (rs, i) -> {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new PayBatch(rs.getLong("id"), rs.getDate("batch_date").toLocalDate(),
                rs.getString("status"), closedAt == null ? null : closedAt.toInstant());
    };

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按批次日期锁定批次行（不存在则创建），供封账/入账串行化使用。
     * 必须在事务内调用。
     */
    public PayBatch lockOrCreate(LocalDate batchDate) {
        Optional<PayBatch> existing = lockForUpdate(batchDate);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            jdbc.update("INSERT INTO pay_batch (batch_date, status) VALUES (?, ?)",
                    batchDate, PayBatch.OPEN);
        } catch (DuplicateKeyException e) {
            // 并发创建同一批次：忽略，重新加锁读取
        }
        return lockForUpdate(batchDate).orElseThrow(() -> new IllegalStateException("批次创建失败: " + batchDate));
    }

    public Optional<PayBatch> lockForUpdate(LocalDate batchDate) {
        return jdbc.query("SELECT id, batch_date, status, closed_at FROM pay_batch WHERE batch_date = ? FOR UPDATE",
                MAPPER, batchDate).stream().findFirst();
    }

    public Optional<PayBatch> findByDate(LocalDate batchDate) {
        return jdbc.query("SELECT id, batch_date, status, closed_at FROM pay_batch WHERE batch_date = ?",
                MAPPER, batchDate).stream().findFirst();
    }

    public void markClosed(long batchId) {
        jdbc.update("UPDATE pay_batch SET status = ?, closed_at = ? WHERE id = ?",
                PayBatch.CLOSED, Timestamp.from(java.time.Instant.now()), batchId);
    }
}
