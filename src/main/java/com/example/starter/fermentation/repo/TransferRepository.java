package com.example.starter.fermentation.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 清酒转出仓储：每批唯一转出记录，重复转出返回首次记录实现幂等成功。
 */
@Repository
public class TransferRepository {

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入转出记录；batch_id 唯一键保证终态只落一次。
     */
    public void insert(long batchId, Instant transferredAt, String requestId) {
        jdbc.sql("""
                        INSERT INTO transfer_record (batch_id, transferred_at, request_id)
                        VALUES (?, ?, ?)
                        """)
                .param(batchId)
                .param(Timestamp.from(transferredAt))
                .param(requestId)
                .update();
    }

    /**
     * 查询批次已有的转出时刻，终态幂等返回用。
     */
    public Optional<Instant> findTransferredAt(long batchId) {
        Timestamp timestamp = jdbc.sql("SELECT transferred_at FROM transfer_record WHERE batch_id = ?")
                .param(batchId)
                .query(Timestamp.class)
                .optional()
                .orElse(null);
        return Optional.ofNullable(timestamp == null ? null : timestamp.toInstant());
    }
}
