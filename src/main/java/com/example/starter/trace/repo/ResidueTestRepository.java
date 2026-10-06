package com.example.starter.trace.repo;

import com.example.starter.trace.TestConclusion;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 药残检测链与检测记录的数据访问。
 */
@Repository
public class ResidueTestRepository {

    private final JdbcTemplate jdbc;

    public ResidueTestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 开启一条检测链并返回链 ID。
     */
    public long insertChain(long batchId, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO test_chain (batch_id, status, created_at) VALUES (?, 'OPEN', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setTimestamp(2, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * 闭环检测链。
     */
    public void closeChain(long chainId, LocalDateTime closedAt) {
        jdbc.update("UPDATE test_chain SET status = 'CLOSED', closed_at = ? WHERE id = ?",
                Timestamp.valueOf(closedAt), chainId);
    }

    /**
     * 占用采收批的未闭环检测链槽位。
     *
     * @return true 占用成功；false 该批已存在未闭环检测链
     */
    public boolean tryOpenChainSlot(long batchId, long chainId) {
        try {
            jdbc.update("INSERT INTO open_chain (batch_id, chain_id) VALUES (?, ?)", batchId, chainId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 释放采收批的未闭环检测链槽位。
     */
    public void releaseChainSlot(long batchId) {
        jdbc.update("DELETE FROM open_chain WHERE batch_id = ?", batchId);
    }

    /**
     * 查询采收批当前未闭环的检测链 ID。
     */
    public Optional<Long> findOpenChainId(long batchId) {
        return jdbc.query("SELECT chain_id FROM open_chain WHERE batch_id = ?",
                        (rs, i) -> rs.getLong("chain_id"), batchId)
                .stream().findFirst();
    }

    /**
     * 写入一条检测记录并返回自增 ID。
     */
    public long insertTest(long chainId, long batchId, String testType, String sampleNo,
                           BigDecimal inhibitionRate, TestConclusion conclusion, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO residue_test (chain_id, batch_id, test_type, sample_no, inhibition_rate, conclusion, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, chainId);
            ps.setLong(2, batchId);
            ps.setString(3, testType);
            ps.setString(4, sampleNo);
            ps.setBigDecimal(5, inhibitionRate);
            ps.setString(6, conclusion.name());
            ps.setTimestamp(7, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * 查询检测链内的全部检测记录，按 ID 升序。
     */
    public List<TestRow> findTestsByChain(long chainId) {
        return jdbc.query(
                "SELECT id, chain_id, batch_id, test_type, sample_no, inhibition_rate, conclusion, created_at"
                        + " FROM residue_test WHERE chain_id = ? ORDER BY id",
                this::mapTest, chainId);
    }

    /**
     * 查询采收批的全部检测记录，按 ID 升序。
     */
    public List<TestRow> findTestsByBatch(long batchId) {
        return jdbc.query(
                "SELECT id, chain_id, batch_id, test_type, sample_no, inhibition_rate, conclusion, created_at"
                        + " FROM residue_test WHERE batch_id = ? ORDER BY id",
                this::mapTest, batchId);
    }

    /**
     * 统计同蜂场同花期被判疑似（快检 SUSPECT）的采收批数量。
     */
    public int countSuspectedBatches(long apiaryId, String flowerPeriod) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT t.batch_id) FROM residue_test t"
                        + " JOIN harvest_batch b ON b.id = t.batch_id"
                        + " JOIN harvest_batch_contribution c ON c.batch_id = t.batch_id"
                        + " WHERE t.test_type = 'RAPID' AND t.conclusion = 'SUSPECT'"
                        + " AND c.apiary_id = ? AND b.flower_period = ?",
                Integer.class, apiaryId, flowerPeriod);
        return count == null ? 0 : count;
    }

    private TestRow mapTest(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new TestRow(rs.getLong("id"), rs.getLong("chain_id"), rs.getLong("batch_id"),
                rs.getString("test_type"), rs.getString("sample_no"), rs.getBigDecimal("inhibition_rate"),
                TestConclusion.valueOf(rs.getString("conclusion")),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    /**
     * 检测记录行。
     */
    public record TestRow(Long id, Long chainId, Long batchId, String testType, String sampleNo,
                          BigDecimal inhibitionRate, TestConclusion conclusion, LocalDateTime createdAt) {
    }
}
