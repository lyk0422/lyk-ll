package com.example.starter.pesticide;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 快检与定量复检数据访问。
 */
@Repository
public class TestDao {

    /**
     * 快检记录行。
     */
    public record TestRow(
            long id,
            String requestId,
            String fingerprint,
            long batchId,
            String item,
            BigDecimal inhibitionRate,
            String result,
            LocalDateTime createdAt) {
    }

    /**
     * 复检记录行。
     */
    public record RecheckRow(
            long id,
            String requestId,
            String fingerprint,
            long batchId,
            String item,
            boolean qualified,
            LocalDateTime createdAt) {
    }

    /**
     * 疑似快检联批次的投影，用于产地预警统计与复算。
     */
    public record SuspectRow(
            long batchId,
            String origin,
            String variety,
            LocalDateTime createdAt) {
    }

    private static final RowMapper<TestRow> TEST_MAPPER = (rs, n) -> new TestRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getLong("batch_id"),
            rs.getString("item"),
            rs.getBigDecimal("inhibition_rate"),
            rs.getString("result"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<RecheckRow> RECHECK_MAPPER = (rs, n) -> new RecheckRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getLong("batch_id"),
            rs.getString("item"),
            rs.getBoolean("qualified"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public TestDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入快检记录；requestId 或批次+项目唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertTest(String requestId, String fingerprint, long batchId, String item,
                           BigDecimal inhibitionRate, String result, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO test_record (request_id, fingerprint, batch_id, item, inhibition_rate, result, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, batchId);
            ps.setString(4, item);
            ps.setBigDecimal(5, inhibitionRate);
            ps.setString(6, result);
            ps.setTimestamp(7, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等请求号查询快检记录。
     */
    public Optional<TestRow> findTestByRequestId(String requestId) {
        List<TestRow> rows = jdbc.query("SELECT * FROM test_record WHERE request_id = ?",
                TEST_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询快检记录（联检建单引用冻结快检结果用）。
     */
    public Optional<TestRow> findTestById(long id) {
        List<TestRow> rows = jdbc.query("SELECT * FROM test_record WHERE id = ?",
                TEST_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 判断批次+项目是否已有快检记录（重复送检判定）。
     */
    public boolean testExists(long batchId, String item) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_record WHERE batch_id = ? AND item = ?",
                Integer.class, batchId, item);
        return count != null && count > 0;
    }

    /**
     * 查询批次全部快检记录。
     */
    public List<TestRow> findTestsByBatch(long batchId) {
        return jdbc.query("SELECT * FROM test_record WHERE batch_id = ? ORDER BY id", TEST_MAPPER, batchId);
    }

    /**
     * 查询指定产地+品种的全部疑似快检（按 UTC 日界统计与台账复算）。
     */
    public List<SuspectRow> findSuspects(String origin, String variety) {
        return jdbc.query(
                "SELECT t.batch_id, b.origin, b.variety, t.created_at FROM test_record t"
                        + " JOIN batch b ON b.id = t.batch_id"
                        + " WHERE b.origin = ? AND b.variety = ? AND t.result = 'SUSPECT'",
                (rs, n) -> new SuspectRow(
                        rs.getLong("batch_id"),
                        rs.getString("origin"),
                        rs.getString("variety"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                origin, variety);
    }

    /**
     * 查询全部疑似快检（台账复算用）。
     */
    public List<SuspectRow> findAllSuspects() {
        return jdbc.query(
                "SELECT t.batch_id, b.origin, b.variety, t.created_at FROM test_record t"
                        + " JOIN batch b ON b.id = t.batch_id WHERE t.result = 'SUSPECT'",
                (rs, n) -> new SuspectRow(
                        rs.getLong("batch_id"),
                        rs.getString("origin"),
                        rs.getString("variety"),
                        rs.getTimestamp("created_at").toLocalDateTime()));
    }

    /**
     * 插入复检记录；requestId 或批次+项目唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertRecheck(String requestId, String fingerprint, long batchId, String item,
                              boolean qualified, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO recheck (request_id, fingerprint, batch_id, item, qualified, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, batchId);
            ps.setString(4, item);
            ps.setBoolean(5, qualified);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等请求号查询复检记录。
     */
    public Optional<RecheckRow> findRecheckByRequestId(String requestId) {
        List<RecheckRow> rows = jdbc.query("SELECT * FROM recheck WHERE request_id = ?",
                RECHECK_MAPPER, requestId);
        return rows.stream().findFirst();
    }
}
