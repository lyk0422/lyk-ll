package com.example.starter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 争议记录数据访问。
 */
@Repository
public class DisputeRepository {

    private final JdbcTemplate jdbc;

    public DisputeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DisputeRow> ROW_MAPPER = (rs, n) -> new DisputeRow(
            rs.getLong("id"),
            rs.getString("voucher_no"),
            rs.getString("request_id"),
            rs.getString("party"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getString("adjustment_voucher_no"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("resolved_at") == null ? null
                    : rs.getTimestamp("resolved_at").toInstant());

    /** 插入争议，requestId 唯一；重复键抛 DuplicateKeyException。 */
    public void insert(String voucherNo, String requestId, String party, String reason,
            String status, Instant now) {
        jdbc.update("""
                INSERT INTO dispute
                    (voucher_no, request_id, party, reason, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, voucherNo, requestId, party, reason, status, Timestamp.from(now));
    }

    /** 按 requestId 查询。 */
    public Optional<DisputeRow> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM dispute WHERE request_id = ?", ROW_MAPPER, requestId)
                .stream().findFirst();
    }

    /** 查询凭证下全部争议，按发起时刻升序。 */
    public List<DisputeRow> findByVoucherNo(String voucherNo) {
        return jdbc.query("""
                SELECT * FROM dispute WHERE voucher_no = ? ORDER BY created_at ASC, id ASC
                """, ROW_MAPPER, voucherNo);
    }

    /** 将凭证下全部挂起中的争议标记为已由调整凭证解决。 */
    public int resolveOpen(String voucherNo, String adjustmentVoucherNo, Instant now) {
        return jdbc.update("""
                UPDATE dispute SET status = 'RESOLVED', adjustment_voucher_no = ?,
                    resolved_at = ?
                WHERE voucher_no = ? AND status = 'OPEN'
                """, adjustmentVoucherNo, Timestamp.from(now), voucherNo);
    }

    /** 争议记录行。 */
    public record DisputeRow(Long id, String voucherNo, String requestId, String party,
            String reason, String status, String adjustmentVoucherNo, Instant createdAt,
            Instant resolvedAt) {
    }
}
