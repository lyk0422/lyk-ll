package com.example.starter.fermentation.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 双乙酰检测链仓储：检测按时间排列，供“连续两次合格且间隔不少于 12 小时”口径复算。
 */
@Repository
public class DiacetylRepository {

    private final JdbcClient jdbc;

    public DiacetylRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一次检测记录，requestId 唯一键兜底幂等。
     */
    public void insert(long batchId, Instant testedAt, BigDecimal valueMgL, String requestId) {
        jdbc.sql("""
                        INSERT INTO diacetyl_test (batch_id, tested_at, value_mg_l, request_id)
                        VALUES (?, ?, ?, ?)
                        """)
                .param(batchId)
                .param(Timestamp.from(testedAt))
                .param(valueMgL)
                .param(requestId)
                .update();
    }

    /**
     * 返回批次全部检测（按检测时刻、插入顺序），用于复算判定链。
     */
    public List<DiacetylRow> findAll(long batchId) {
        return jdbc.sql("""
                        SELECT tested_at, value_mg_l
                        FROM diacetyl_test
                        WHERE batch_id = ?
                        ORDER BY tested_at ASC, id ASC
                        """)
                .param(batchId)
                .query((rs, n) -> new DiacetylRow(
                        rs.getTimestamp("tested_at").toInstant(),
                        rs.getBigDecimal("value_mg_l")))
                .list();
    }

    /**
     * 一次双乙酰检测记录。
     */
    public record DiacetylRow(Instant testedAt, BigDecimal valueMgL) {
    }
}
