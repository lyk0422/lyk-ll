package com.example.starter.trace.repo;

import com.example.starter.trace.api.TraceDtos.AlertView;
import com.example.starter.trace.api.TraceDtos.RapidTestView;
import com.example.starter.trace.api.TraceDtos.RetestView;
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
 * 检测链、快检、复检与蜂场预警的数据访问。
 */
@Repository
public class TestRepository {

    /** 检测链行记录。 */
    public record TestChainRow(Long id, Long batchId, String status) {
    }

    private static final RowMapper<TestChainRow> CHAIN_MAPPER = (rs, n) -> new TestChainRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("status"));

    private static final RowMapper<RapidTestView> RAPID_MAPPER = (rs, n) -> new RapidTestView(
            rs.getLong("id"),
            rs.getString("sample_no"),
            rs.getBigDecimal("inhibition_rate"),
            rs.getString("result"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<RetestView> RETEST_MAPPER = (rs, n) -> new RetestView(
            rs.getLong("id"),
            rs.getString("sample_no"),
            rs.getString("result"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<AlertView> ALERT_MAPPER = (rs, n) -> new AlertView(
            rs.getLong("id"),
            rs.getLong("apiary_id"),
            rs.getString("floral_period"),
            rs.getInt("suspect_count"),
            rs.getTimestamp("triggered_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public TestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询采收批当前检测链（每批至多一行）。
     */
    public Optional<TestChainRow> findChainByBatchId(Long batchId) {
        List<TestChainRow> rows = jdbc.query(
                "SELECT id, batch_id, status FROM test_chain WHERE batch_id = ?",
                CHAIN_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /**
     * 开启一条检测链，返回自增主键。
     */
    public Long insertChain(Long batchId, LocalDateTime openedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO test_chain (batch_id, status, opened_at) VALUES (?, 'OPEN', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setTimestamp(2, Timestamp.valueOf(openedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 将已闭环的链重新置为 OPEN（新一轮检测）。
     */
    public void reopenChain(Long chainId, LocalDateTime openedAt) {
        jdbc.update("UPDATE test_chain SET status = 'OPEN', opened_at = ?, closed_at = NULL WHERE id = ?",
                Timestamp.valueOf(openedAt), chainId);
    }

    /**
     * 闭环检测链。
     */
    public void closeChain(Long chainId, LocalDateTime closedAt) {
        jdbc.update("UPDATE test_chain SET status = 'CLOSED', closed_at = ? WHERE id = ?",
                Timestamp.valueOf(closedAt), chainId);
    }

    /**
     * 写入快检记录，返回自增主键。
     */
    public Long insertRapidTest(Long batchId, Long chainId, String sampleNo, BigDecimal inhibitionRate,
                                String result, String requestId, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rapid_test (batch_id, chain_id, sample_no, inhibition_rate, result, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setLong(2, chainId);
            ps.setString(3, sampleNo);
            ps.setBigDecimal(4, inhibitionRate);
            ps.setString(5, result);
            ps.setString(6, requestId);
            ps.setTimestamp(7, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 写入复检记录，返回自增主键。
     */
    public Long insertRetest(Long batchId, Long chainId, String sampleNo, String result,
                             String requestId, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO retest (batch_id, chain_id, sample_no, result, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setLong(2, chainId);
            ps.setString(3, sampleNo);
            ps.setString(4, result);
            ps.setString(5, requestId);
            ps.setTimestamp(6, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询采收批全部快检记录（含被复检推翻的记录）。
     */
    public List<RapidTestView> findRapidTests(Long batchId) {
        return jdbc.query(
                "SELECT id, sample_no, inhibition_rate, result, created_at FROM rapid_test WHERE batch_id = ? ORDER BY id",
                RAPID_MAPPER, batchId);
    }

    /**
     * 查询采收批全部复检记录。
     */
    public List<RetestView> findRetests(Long batchId) {
        return jdbc.query(
                "SELECT id, sample_no, result, created_at FROM retest WHERE batch_id = ? ORDER BY id",
                RETEST_MAPPER, batchId);
    }

    /**
     * 查询指定检测链上的快检样品编号列表，用于复检样品去重校验。
     */
    public List<String> findRapidSampleNos(Long chainId) {
        return jdbc.queryForList(
                "SELECT sample_no FROM rapid_test WHERE chain_id = ?", String.class, chainId);
    }

    /**
     * 统计同蜂场同花期累计疑似批次数（按贡献关联）。
     */
    public int countSuspectBatches(Long apiaryId, String floralPeriod) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT b.id) FROM harvest_batch b "
                        + "JOIN harvest_contribution c ON c.batch_id = b.id "
                        + "JOIN rapid_test r ON r.batch_id = b.id "
                        + "WHERE c.apiary_id = ? AND b.floral_period = ? AND r.result = 'SUSPECT'",
                Integer.class, apiaryId, floralPeriod);
        return count == null ? 0 : count;
    }

    /**
     * 触发蜂场预警；唯一约束保证同蜂场同花期仅一条，返回是否真正插入。
     */
    public boolean insertAlertIfAbsent(Long apiaryId, String floralPeriod, int suspectCount,
                                       LocalDateTime triggeredAt) {
        int updated = jdbc.update(
                "INSERT INTO apiary_alert (apiary_id, floral_period, suspect_count, triggered_at)"
                        + " SELECT ?, ?, ?, ? FROM DUAL WHERE NOT EXISTS ("
                        + " SELECT 1 FROM apiary_alert WHERE apiary_id = ? AND floral_period = ?)",
                apiaryId, floralPeriod, suspectCount, Timestamp.valueOf(triggeredAt),
                apiaryId, floralPeriod);
        return updated > 0;
    }

    /**
     * 查询蜂场全部预警。
     */
    public List<AlertView> findAlertsByApiary(Long apiaryId) {
        return jdbc.query(
                "SELECT id, apiary_id, floral_period, suspect_count, triggered_at FROM apiary_alert"
                        + " WHERE apiary_id = ? ORDER BY id",
                ALERT_MAPPER, apiaryId);
    }
}
