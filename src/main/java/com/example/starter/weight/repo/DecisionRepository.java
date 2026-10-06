package com.example.starter.weight.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.weight.repo.po.DecisionRow;

/**
 * 处罚决定/文书仓储：按幂等键唯一、撤销状态流转、文书链查询。
 */
@Repository
public class DecisionRepository {

    private static final RowMapper<DecisionRow> MAPPER = (rs, n) -> new DecisionRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("doc_no"),
            rs.getLong("case_id"),
            rs.getString("doc_type"),
            rs.getString("tier"),
            rs.getBigDecimal("total_kg"),
            rs.getBigDecimal("limit_kg"),
            rs.getBigDecimal("overload_rate"),
            rs.getBigDecimal("fine_amount"),
            rs.getInt("points"),
            rs.getString("status"),
            rs.getTimestamp("served_at").toInstant(),
            toInstant(rs.getTimestamp("revoked_at")),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public DecisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查询；不存在返回 null。
     */
    public DecisionRow findByRequestId(String requestId) {
        try {
            return jdbc.queryForObject("SELECT * FROM decision_doc WHERE request_id = ?", MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 按主键查询并加行锁（须在事务内调用）；不存在返回 null。
     */
    public DecisionRow findByIdForUpdate(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM decision_doc WHERE id = ? FOR UPDATE", MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 按主键查询（不加锁）；不存在返回 null。
     */
    public DecisionRow findById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM decision_doc WHERE id = ?", MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 查询案件下当前生效的处罚决定（ACTIVE）；不存在返回 null。
     */
    public DecisionRow findActiveByCaseId(long caseId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM decision_doc WHERE case_id = ? AND status = 'ACTIVE' ORDER BY id",
                    MAPPER, caseId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 查询案件全部文书（文书链，可复算核查），按生成顺序排列。
     */
    public List<DecisionRow> findByCaseId(long caseId) {
        return jdbc.query("SELECT * FROM decision_doc WHERE case_id = ? ORDER BY id", MAPPER, caseId);
    }

    /**
     * 插入文书，返回生成的主键。
     */
    public long insert(DecisionRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO decision_doc
                      (request_id, fingerprint, doc_no, case_id, doc_type, tier, total_kg, limit_kg,
                       overload_rate, fine_amount, points, status, served_at, revoked_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                    """, new String[] {"id"});
            ps.setString(1, row.requestId());
            ps.setString(2, row.fingerprint());
            ps.setString(3, row.docNo());
            ps.setLong(4, row.caseId());
            ps.setString(5, row.docType());
            ps.setString(6, row.tier());
            ps.setBigDecimal(7, row.totalKg());
            ps.setBigDecimal(8, row.limitKg());
            ps.setBigDecimal(9, row.overloadRate());
            ps.setBigDecimal(10, row.fineAmount());
            ps.setInt(11, row.points());
            ps.setString(12, row.status());
            ps.setTimestamp(13, Timestamp.from(row.servedAt()));
            ps.setTimestamp(14, Timestamp.from(row.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 撤销原处罚决定（须在复检推翻事务内调用）。
     *
     * @return 受影响行数；0 表示决定不存在或已撤销
     */
    public int revoke(long id, Instant revokedAt) {
        return jdbc.update(
                "UPDATE decision_doc SET status = 'REVOKED', revoked_at = ? WHERE id = ? AND status = 'ACTIVE'",
                Timestamp.from(revokedAt), id);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
