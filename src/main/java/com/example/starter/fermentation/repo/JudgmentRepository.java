package com.example.starter.fermentation.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 成熟度判定记录仓储：requestId 唯一键兜底幂等，口径明细随记录保存以便复算。
 */
@Repository
public class JudgmentRepository {

    private final JdbcClient jdbc;

    public JudgmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一次综合判定记录。
     */
    public void insert(long batchId,
                       Instant judgedAt,
                       boolean passed,
                       boolean extended,
                       String detail,
                       String requestId) {
        jdbc.sql("""
                        INSERT INTO maturity_judgment
                            (batch_id, judged_at, passed, extended, detail, request_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)
                .param(batchId)
                .param(Timestamp.from(judgedAt))
                .param(passed)
                .param(extended)
                .param(detail)
                .param(requestId)
                .update();
    }
}
