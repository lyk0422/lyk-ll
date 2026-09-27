package com.example.rebate.repo;

import com.example.rebate.domain.ChargebackOrder;
import com.example.rebate.domain.ChargebackStatus;
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
 * 冲回单数据访问。同批次同更正编号唯一由数据库约束保证；批准/驳回以
 * {@code status='PENDING' AND version_no=expectedVersion} 条件更新做并发与乐观版本保障。
 */
@Repository
public class ChargebackOrderRepository {

    private static final RowMapper<ChargebackOrder> MAPPER = new OrderRowMapper();

    private final JdbcTemplate jdbc;

    public ChargebackOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入冲回单（初版 PENDING，version=1），回填自增主键。 */
    public long insert(ChargebackOrder o) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_chargeback_order
                        (batch_id, check_detail_id, declaration_no, correction_no, reason,
                         amount_cents, status, submitter, reviewer, version_no,
                         submitted_at, reviewed_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, o.batchId());
            ps.setLong(2, o.checkDetailId());
            ps.setString(3, o.declarationNo());
            ps.setString(4, o.correctionNo());
            ps.setString(5, o.reason());
            ps.setLong(6, o.amountCents());
            ps.setString(7, o.status().name());
            ps.setString(8, o.submitter());
            ps.setString(9, o.reviewer());
            ps.setInt(10, o.version());
            ps.setTimestamp(11, Timestamp.from(o.submittedAt()));
            ps.setTimestamp(12, o.reviewedAt() == null ? null : Timestamp.from(o.reviewedAt()));
            ps.setTimestamp(13, Timestamp.from(o.createdAt()));
            ps.setTimestamp(14, Timestamp.from(o.updatedAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ChargebackOrder> findById(long id) {
        List<ChargebackOrder> list = jdbc.query(BASE_SELECT + " WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public Optional<ChargebackOrder> findByBatchIdAndCorrectionNo(long batchId, String correctionNo) {
        List<ChargebackOrder> list = jdbc.query(
                BASE_SELECT + " WHERE batch_id = ? AND correction_no = ?",
                MAPPER, batchId, correctionNo);
        return list.stream().findFirst();
    }

    public List<ChargebackOrder> findByBatchIdAndDeclarationNo(long batchId, String declarationNo) {
        return jdbc.query(
                BASE_SELECT + " WHERE batch_id = ? AND declaration_no = ? ORDER BY id",
                MAPPER, batchId, declarationNo);
    }

    /**
     * 复核（批准或驳回）的条件更新：仅当冲回单仍为 PENDING 且版本匹配时生效。
     *
     * @return 受影响行数；0 表示已被并发复核或 expectedVersion 不匹配
     */
    public int casReview(long orderId, ChargebackStatus newStatus, String reviewer,
                         int expectedVersion, Instant now) {
        return jdbc.update("""
                UPDATE t_chargeback_order
                SET status = ?, reviewer = ?, version_no = version_no + 1,
                    reviewed_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PENDING' AND version_no = ?
                """,
                newStatus.name(), reviewer, Timestamp.from(now), Timestamp.from(now),
                orderId, expectedVersion);
    }

    private static final String BASE_SELECT =
            "SELECT id, batch_id, check_detail_id, declaration_no, correction_no, reason, "
                    + "amount_cents, status, submitter, reviewer, version_no, submitted_at, "
                    + "reviewed_at, created_at, updated_at FROM t_chargeback_order";

    private static final class OrderRowMapper implements RowMapper<ChargebackOrder> {
        @Override
        public ChargebackOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp reviewed = rs.getTimestamp("reviewed_at");
            return new ChargebackOrder(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getLong("check_detail_id"),
                    rs.getString("declaration_no"),
                    rs.getString("correction_no"),
                    rs.getString("reason"),
                    rs.getLong("amount_cents"),
                    ChargebackStatus.valueOf(rs.getString("status")),
                    rs.getString("submitter"),
                    rs.getString("reviewer"),
                    rs.getInt("version_no"),
                    rs.getTimestamp("submitted_at").toInstant(),
                    reviewed == null ? null : reviewed.toInstant(),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }
    }
}
