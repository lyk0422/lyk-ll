package com.example.rebate.repo;

import com.example.rebate.domain.ReversalQuota;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 逐单可冲余额台账数据访问。
 *
 * <p>占用采用条件 UPDATE（{@code occupied + amount <= frozen}）作为最终并发保障：
 * 两笔冲回并发批准时，台账行锁串行化，超额一方更新 0 行并整体回滚。</p>
 */
@Repository
public class ReversalQuotaRepository {

    private static final RowMapper<ReversalQuota> MAPPER = new QuotaRowMapper();

    private final JdbcTemplate jdbc;

    public ReversalQuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 首次为结案批次内某报关单建立台账行（冻结额固定，不随后续冲回变化）。
     *
     * @return 是否新建；并发下另一事务已建返回 {@code false}
     */
    public boolean insertIfAbsent(long batchId, String declarationNo, long frozenTaxCents, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO t_reversal_quota
                        (batch_id, declaration_no, frozen_tax_cents, occupied_cents, created_at, updated_at)
                    VALUES (?, ?, ?, 0, ?, ?)
                    """,
                    batchId, declarationNo, frozenTaxCents, Timestamp.from(now), Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 条件占用：仅当占用后不超过冻结额（恰归零合法）时才更新。
     *
     * @return 更新行数；0 表示剩余可冲额不足，调用方必须回滚
     */
    public int occupyIfWithinFrozen(long quotaId, long amountCents, Instant now) {
        return jdbc.update("""
                UPDATE t_reversal_quota
                SET occupied_cents = occupied_cents + ?, updated_at = ?
                WHERE id = ? AND occupied_cents + ? <= frozen_tax_cents
                """,
                amountCents, Timestamp.from(now), quotaId, amountCents);
    }

    public Optional<ReversalQuota> findByBatchAndDeclaration(long batchId, String declarationNo) {
        List<ReversalQuota> list = jdbc.query(BASE_SELECT
                + " WHERE batch_id = ? AND declaration_no = ?", MAPPER, batchId, declarationNo);
        return list.stream().findFirst();
    }

    private static final String BASE_SELECT =
            "SELECT id, batch_id, declaration_no, frozen_tax_cents, occupied_cents, created_at, updated_at "
                    + "FROM t_reversal_quota";

    private static final class QuotaRowMapper implements RowMapper<ReversalQuota> {
        @Override
        public ReversalQuota mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ReversalQuota(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getString("declaration_no"),
                    rs.getLong("frozen_tax_cents"),
                    rs.getLong("occupied_cents"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }
    }
}
