package com.example.rebate.repo;

import com.example.rebate.domain.ClawbackQuota;
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
 * 逐单（批次＋报关单）可冲额度数据访问。
 *
 * <p>批准冲回时以 {@code SELECT ... FOR UPDATE} 锁定对应额度行串行化并发批准，保证累计占用不超冻结额。</p>
 */
@Repository
public class ClawbackQuotaRepository {

    private static final RowMapper<ClawbackQuota> MAPPER = new QuotaRowMapper();

    private static final String BASE_SELECT =
            "SELECT batch_id, declaration_no, frozen_tax_cents, occupied_cents, version_no, "
                    + "created_at, updated_at FROM t_clawback_quota";

    private final JdbcTemplate jdbc;

    public ClawbackQuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 首次引用某报关单额度时插入冻结额快照（占用 0、版本 1）。
     *
     * @return {@code true} 插入成功；{@code false} 已存在（并发首单）
     */
    public boolean insertIfAbsent(long batchId, String declarationNo, long frozenTaxCents, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO t_clawback_quota
                        (batch_id, declaration_no, frozen_tax_cents, occupied_cents, version_no,
                         created_at, updated_at)
                    VALUES (?, ?, ?, 0, 1, ?, ?)
                    """,
                    batchId, declarationNo, frozenTaxCents, Timestamp.from(now), Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<ClawbackQuota> find(long batchId, String declarationNo) {
        List<ClawbackQuota> list = jdbc.query(
                BASE_SELECT + " WHERE batch_id = ? AND declaration_no = ?",
                MAPPER, batchId, declarationNo);
        return list.stream().findFirst();
    }

    /**
     * 行锁读取额度（仅应在事务内调用），并发批准在此串行。
     */
    public Optional<ClawbackQuota> findForUpdate(long batchId, String declarationNo) {
        List<ClawbackQuota> list = jdbc.query(
                BASE_SELECT + " WHERE batch_id = ? AND declaration_no = ? FOR UPDATE",
                MAPPER, batchId, declarationNo);
        return list.stream().findFirst();
    }

    /**
     * 占用额度并把版本加 1；批准冲回的同一事务内、已持行锁时调用。
     */
    public void occupy(long batchId, String declarationNo, long additionalCents, Instant now) {
        jdbc.update("""
                UPDATE t_clawback_quota
                SET occupied_cents = occupied_cents + ?,
                    version_no = version_no + 1,
                    updated_at = ?
                WHERE batch_id = ? AND declaration_no = ?
                """,
                additionalCents, Timestamp.from(now), batchId, declarationNo);
    }

    private static final class QuotaRowMapper implements RowMapper<ClawbackQuota> {
        @Override
        public ClawbackQuota mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClawbackQuota(
                    rs.getLong("batch_id"),
                    rs.getString("declaration_no"),
                    rs.getLong("frozen_tax_cents"),
                    rs.getLong("occupied_cents"),
                    rs.getInt("version_no"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }
    }
}
