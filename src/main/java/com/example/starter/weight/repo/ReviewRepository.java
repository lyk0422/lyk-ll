package com.example.starter.weight.repo;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.weight.repo.po.ReviewRow;

/**
 * 复检申诉仓储：申请幂等、案件维度唯一受理（唯一约束保证并发唯一）、结论幂等。
 */
@Repository
public class ReviewRepository {

    private static final RowMapper<ReviewRow> MAPPER = (rs, n) -> new ReviewRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getLong("case_id"),
            rs.getLong("decision_id"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getTimestamp("applied_at").toInstant(),
            toInstant(rs.getTimestamp("concluded_at")),
            rs.getString("conclude_request_id"),
            rs.getString("conclude_fingerprint"),
            rs.getBigDecimal("recheck_total_kg"));

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按申请幂等键查询；不存在返回 null。
     */
    public ReviewRow findByRequestId(String requestId) {
        try {
            return jdbc.queryForObject("SELECT * FROM review_request WHERE request_id = ?", MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 按结论幂等键查询；不存在返回 null。
     */
    public ReviewRow findByConcludeRequestId(String concludeRequestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM review_request WHERE conclude_request_id = ?", MAPPER, concludeRequestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 按案件查询复检申请；不存在返回 null。
     */
    public ReviewRow findByCaseId(long caseId) {
        try {
            return jdbc.queryForObject("SELECT * FROM review_request WHERE case_id = ?", MAPPER, caseId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 按主键查询并加行锁（须在事务内调用）；不存在返回 null。
     */
    public ReviewRow findByIdForUpdate(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM review_request WHERE id = ? FOR UPDATE", MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 插入复检申请，返回生成的主键。
     */
    public long insert(ReviewRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO review_request
                      (request_id, fingerprint, case_id, decision_id, reason, status,
                       applied_at, concluded_at, conclude_request_id, conclude_fingerprint, recheck_total_kg)
                    VALUES (?, ?, ?, ?, ?, 'UNDER_REVIEW', ?, NULL, NULL, NULL, ?)
                    """, new String[] {"id"});
            ps.setString(1, row.requestId());
            ps.setString(2, row.fingerprint());
            ps.setLong(3, row.caseId());
            ps.setLong(4, row.decisionId());
            ps.setString(5, row.reason());
            ps.setTimestamp(6, Timestamp.from(row.appliedAt()));
            if (row.recheckTotalKg() == null) {
                ps.setObject(7, null);
            } else {
                ps.setBigDecimal(7, row.recheckTotalKg());
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 写入复检结论。仅对待结论申请生效。
     *
     * @return 受影响行数；0 表示申请不存在或已有结论
     */
    public int conclude(long id, String status, String concludeRequestId, String concludeFingerprint,
                        Instant concludedAt, java.math.BigDecimal recheckTotalKg) {
        return jdbc.update("""
                UPDATE review_request
                SET status = ?, concluded_at = ?, conclude_request_id = ?, conclude_fingerprint = ?,
                    recheck_total_kg = ?
                WHERE id = ? AND status = 'UNDER_REVIEW'
                """, status, Timestamp.from(concludedAt), concludeRequestId, concludeFingerprint,
                recheckTotalKg, id);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
