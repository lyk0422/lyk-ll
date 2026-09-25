package com.example.starter.inspection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.TestVerdict;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 快检记录数据访问。
 */
@Repository
public class RapidTestRepository {

    private final JdbcClient jdbc;

    public RapidTestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入快检记录，（批次＋项目）唯一约束拒绝重复送检；返回生成主键。
     */
    public long insert(String requestId, String fingerprint, long batchId, String item,
                       java.math.BigDecimal inhibitionRate, TestVerdict verdict, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO rapid_test (request_id, fingerprint, batch_id, item, inhibition_rate, verdict, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(requestId, fingerprint, batchId, item, inhibitionRate, verdict.name(), now)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<RapidTest> findByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM rapid_test WHERE request_id = ?")
                .param(requestId)
                .query(this::map)
                .optional();
    }

    public Optional<RapidTest> findByBatchAndItem(long batchId, String item) {
        return jdbc.sql("SELECT * FROM rapid_test WHERE batch_id = ? AND item = ?")
                .params(batchId, item)
                .query(this::map)
                .optional();
    }

    public List<RapidTest> findByBatchId(long batchId) {
        return jdbc.sql("SELECT * FROM rapid_test WHERE batch_id = ? ORDER BY id")
                .param(batchId)
                .query(this::map)
                .list();
    }

    /**
     * 统计某产地某品种在指定 UTC 日期的疑似超标批次数（用于产地预警台账复算）。
     */
    public long countSuspected(String origin, String variety, LocalDate date) {
        return jdbc.sql("SELECT COUNT(*) FROM rapid_test rt JOIN batch b ON rt.batch_id = b.id"
                        + " WHERE rt.verdict = 'SUSPECTED' AND b.origin = ? AND b.variety = ?"
                        + " AND CAST(rt.created_at AS DATE) = ?")
                .params(origin, variety, date)
                .query(Long.class)
                .single();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM rapid_test").query(Long.class).single();
    }

    private RapidTest map(ResultSet rs, int rowNum) throws SQLException {
        return new RapidTest(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("batch_id"),
                rs.getString("item"),
                rs.getBigDecimal("inhibition_rate"),
                TestVerdict.valueOf(rs.getString("verdict")),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
