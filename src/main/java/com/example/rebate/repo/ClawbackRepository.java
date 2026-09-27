package com.example.rebate.repo;

import com.example.rebate.domain.Clawback;
import com.example.rebate.domain.ClawbackStatus;
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
 * 冲回单数据访问。
 */
@Repository
public class ClawbackRepository {

    private static final RowMapper<Clawback> MAPPER = new ClawbackRowMapper();

    private static final String BASE_SELECT =
            "SELECT id, batch_id, batch_no, declaration_no, external_correction_no, reason, "
                    + "amount_cents, frozen_tax_cents, status, submitted_by, reviewer, reviewed_at, "
                    + "version_no, created_at, updated_at FROM t_clawback";

    private final JdbcTemplate jdbc;

    public ClawbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入待复核冲回单（版本 1），回填自增主键。
     */
    public long insert(long batchId, String batchNo, String declarationNo,
                       String externalCorrectionNo, String reason, long amountCents,
                       long frozenTaxCents, String submittedBy, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_clawback
                        (batch_id, batch_no, declaration_no, external_correction_no, reason,
                         amount_cents, frozen_tax_cents, status, submitted_by, version_no,
                         created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, batchNo);
            ps.setString(3, declarationNo);
            ps.setString(4, externalCorrectionNo);
            ps.setString(5, reason);
            ps.setLong(6, amountCents);
            ps.setLong(7, frozenTaxCents);
            ps.setString(8, ClawbackStatus.PENDING.name());
            ps.setString(9, submittedBy);
            ps.setTimestamp(10, Timestamp.from(now));
            ps.setTimestamp(11, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Clawback> findById(long id) {
        return jdbc.query(BASE_SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Clawback> findByBatchAndCorrectionNo(long batchId, String correctionNo) {
        return jdbc.query(
                BASE_SELECT + " WHERE batch_id = ? AND external_correction_no = ?",
                MAPPER, batchId, correctionNo).stream().findFirst();
    }

    public List<Clawback> findByBatchId(long batchId) {
        return jdbc.query(BASE_SELECT + " WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    public List<Clawback> findByBatchAndDeclaration(long batchId, String declarationNo) {
        return jdbc.query(
                BASE_SELECT + " WHERE batch_id = ? AND declaration_no = ? ORDER BY id",
                MAPPER, batchId, declarationNo);
    }

    /**
     * 乐观复核：仅当仍为 {@code PENDING} 且版本匹配时更新为目标状态，版本加 1。
     *
     * @return 受影响行数；0 表示状态或版本已变化
     */
    public int review(long id, ClawbackStatus status, String reviewer,
                      int expectedVersion, Instant now) {
        return jdbc.update("""
                UPDATE t_clawback
                SET status = ?, reviewer = ?, reviewed_at = ?,
                    version_no = version_no + 1, updated_at = ?
                WHERE id = ? AND status = ? AND version_no = ?
                """,
                status.name(), reviewer, Timestamp.from(now), Timestamp.from(now),
                id, ClawbackStatus.PENDING.name(), expectedVersion);
    }

    /**
     * 登记一笔追缴回执成功后乐观变更：版本加 1；恰收齐时同时置 COMPLETED，否则保持 APPROVED。
     *
     * @return 受影响行数；0 表示状态或版本已变化，调用方整笔回滚并返回 409
     */
    public int applyReceipt(long id, int expectedVersion, boolean completed, Instant now) {
        ClawbackStatus nextStatus =
                completed ? ClawbackStatus.COMPLETED : ClawbackStatus.APPROVED;
        return jdbc.update("""
                UPDATE t_clawback
                SET status = ?, version_no = version_no + 1, updated_at = ?
                WHERE id = ? AND status = ? AND version_no = ?
                """,
                nextStatus.name(), Timestamp.from(now), id,
                ClawbackStatus.APPROVED.name(), expectedVersion);
    }

    private static final class ClawbackRowMapper implements RowMapper<Clawback> {
        @Override
        public Clawback mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Clawback(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getString("batch_no"),
                    rs.getString("declaration_no"),
                    rs.getString("external_correction_no"),
                    rs.getString("reason"),
                    rs.getLong("amount_cents"),
                    rs.getLong("frozen_tax_cents"),
                    ClawbackStatus.valueOf(rs.getString("status")),
                    rs.getString("submitted_by"),
                    rs.getString("reviewer"),
                    toInstant(rs.getTimestamp("reviewed_at")),
                    rs.getInt("version_no"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }

        private static Instant toInstant(Timestamp ts) {
            return ts == null ? null : ts.toInstant();
        }
    }
}
