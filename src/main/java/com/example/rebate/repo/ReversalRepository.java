package com.example.rebate.repo;

import com.example.rebate.domain.Reversal;
import com.example.rebate.domain.ReversalStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 冲回单数据访问：插入、乐观版本状态流转与追缴累计条件更新。
 */
@Repository
public class ReversalRepository {

    private static final RowMapper<Reversal> MAPPER = new ReversalRowMapper();

    private final JdbcTemplate jdbc;

    public ReversalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 提交冲回单，初为待复核、版本 1，回填自增主键。
     */
    public long insert(long batchId, String declarationNo, String correctionNo, String reason,
                       long amountCents, String submittedBy, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_reversal
                        (batch_id, declaration_no, correction_no, reason, amount_cents, status,
                         submitted_by, submitted_at, approved_cents, collected_cents,
                         version_no, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 1, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, declarationNo);
            ps.setString(3, correctionNo);
            ps.setString(4, reason);
            ps.setLong(5, amountCents);
            ps.setString(6, ReversalStatus.PENDING.name());
            ps.setString(7, submittedBy);
            ps.setTimestamp(8, Timestamp.from(now));
            ps.setTimestamp(9, Timestamp.from(now));
            ps.setTimestamp(10, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 乐观批准：仅当 {@code version_no = expectedVersion} 且仍为 PENDING 时流转，版本加 1。
     *
     * @return 更新行数；0 表示版本不符或状态已变
     */
    public int approve(long id, int expectedVersion, String reviewedBy,
                       long approvedCents, Instant now) {
        return jdbc.update("""
                UPDATE t_reversal
                SET status = ?, reviewed_by = ?, reviewed_at = ?, reject_reason = NULL,
                    approved_cents = ?, version_no = version_no + 1, updated_at = ?
                WHERE id = ? AND version_no = ? AND status = ?
                """,
                ReversalStatus.APPROVED.name(), reviewedBy, Timestamp.from(now),
                approvedCents, Timestamp.from(now),
                id, expectedVersion, ReversalStatus.PENDING.name());
    }

    /**
     * 乐观驳回：仅当 {@code version_no = expectedVersion} 且仍为 PENDING 时流转，不占额。
     */
    public int reject(long id, int expectedVersion, String reviewedBy,
                      String rejectReason, Instant now) {
        return jdbc.update("""
                UPDATE t_reversal
                SET status = ?, reviewed_by = ?, reviewed_at = ?, reject_reason = ?,
                    version_no = version_no + 1, updated_at = ?
                WHERE id = ? AND version_no = ? AND status = ?
                """,
                ReversalStatus.REJECTED.name(), reviewedBy, Timestamp.from(now),
                rejectReason, Timestamp.from(now),
                id, expectedVersion, ReversalStatus.PENDING.name());
    }

    /**
     * 登记追缴到账：条件累计（不超过批准额），恰收齐转 COMPLETED，版本加 1。
     *
     * <p>仅 APPROVED 且版本匹配时可更新；更新 0 行表示版本不符、状态非法或将超额。</p>
     *
     * @return 更新行数
     */
    public int addReceiptAmount(long id, int expectedVersion, long amountCents, Instant now) {
        return jdbc.update("""
                UPDATE t_reversal
                SET collected_cents = collected_cents + ?,
                    status = CASE WHEN collected_cents + ? = approved_cents
                                  THEN ? ELSE status END,
                    completed_at = CASE WHEN collected_cents + ? = approved_cents
                                        THEN ? ELSE completed_at END,
                    version_no = version_no + 1,
                    updated_at = ?
                WHERE id = ? AND version_no = ? AND status = ?
                      AND collected_cents + ? <= approved_cents
                """,
                amountCents, amountCents, ReversalStatus.COMPLETED.name(),
                amountCents, Timestamp.from(now),
                Timestamp.from(now),
                id, expectedVersion, ReversalStatus.APPROVED.name(), amountCents);
    }

    public Optional<Reversal> findById(long id) {
        List<Reversal> list = jdbc.query(BASE_SELECT + " WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public Optional<Reversal> findByBatchAndCorrectionNo(long batchId, String correctionNo) {
        List<Reversal> list = jdbc.query(
                BASE_SELECT + " WHERE batch_id = ? AND correction_no = ?",
                MAPPER, batchId, correctionNo);
        return list.stream().findFirst();
    }

    public List<Reversal> findByBatchId(long batchId) {
        return jdbc.query(BASE_SELECT + " WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    private static final String BASE_SELECT =
            "SELECT id, batch_id, declaration_no, correction_no, reason, amount_cents, status, "
                    + "submitted_by, submitted_at, reviewed_by, reviewed_at, reject_reason, "
                    + "approved_cents, collected_cents, completed_at, version_no, created_at, updated_at "
                    + "FROM t_reversal";

    private static final class ReversalRowMapper implements RowMapper<Reversal> {
        @Override
        public Reversal mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Reversal(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getString("declaration_no"),
                    rs.getString("correction_no"),
                    rs.getString("reason"),
                    rs.getLong("amount_cents"),
                    ReversalStatus.valueOf(rs.getString("status")),
                    rs.getString("submitted_by"),
                    rs.getTimestamp("submitted_at").toInstant(),
                    rs.getString("reviewed_by"),
                    toInstant(rs.getTimestamp("reviewed_at")),
                    rs.getString("reject_reason"),
                    rs.getLong("approved_cents"),
                    rs.getLong("collected_cents"),
                    toInstant(rs.getTimestamp("completed_at")),
                    rs.getInt("version_no"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }

        private static Instant toInstant(Timestamp ts) {
            return ts == null ? null : ts.toInstant();
        }
    }
}
