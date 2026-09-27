package com.example.rebate.repo;

import com.example.rebate.domain.ReversalLedger;
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
 * 冲回可退余额台账数据访问（逐报关单一行，懒创建）。
 *
 * <p>占用与到账均以带边界条件的 UPDATE 完成：数据库行锁串行并发，
 * {@code occupied + amount <= frozen} / {@code received + amount <= occupied} 不成立时更新 0 行，
 * 从根本上防止并发超冲、超收。</p>
 */
@Repository
public class ReversalLedgerRepository {

    private static final RowMapper<ReversalLedger> MAPPER = new LedgerRowMapper();

    private static final String BASE_SELECT =
            "SELECT id, batch_id, declaration_no, frozen_cents, occupied_cents, received_cents, "
                    + "version_no, created_at, updated_at FROM t_reversal_ledger";

    private final JdbcTemplate jdbc;

    public ReversalLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 首次冲回提交时懒建台账行（版本从 1 开始）。并发下唯一键冲突返回 {@code false}，由调用方重读。
     */
    public boolean insertIfAbsent(long batchId, String declarationNo, long frozenCents, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO t_reversal_ledger
                        (batch_id, declaration_no, frozen_cents, occupied_cents, received_cents,
                         version_no, created_at, updated_at)
                    VALUES (?, ?, ?, 0, 0, 1, ?, ?)
                    """,
                    batchId, declarationNo, frozenCents, Timestamp.from(now), Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<ReversalLedger> find(long batchId, String declarationNo) {
        List<ReversalLedger> list = jdbc.query(
                BASE_SELECT + " WHERE batch_id = ? AND declaration_no = ?",
                MAPPER, batchId, declarationNo);
        return list.stream().findFirst();
    }

    /**
     * 批准占用：仅当占用后不超过冻结额时更新成功，并把台账版本 +1。
     *
     * @return 实际更新行数（0 表示剩余可冲额不足）
     */
    public int occupy(long ledgerId, long amountCents, Instant now) {
        return jdbc.update("""
                UPDATE t_reversal_ledger
                SET occupied_cents = occupied_cents + ?,
                    version_no = version_no + 1,
                    updated_at = ?
                WHERE id = ? AND occupied_cents + ? <= frozen_cents
                """,
                amountCents, Timestamp.from(now), ledgerId, amountCents);
    }

    /**
     * 回执到账：仅当累计到账不超过已占用额时更新成功，并把台账版本 +1。
     *
     * @return 实际更新行数（0 表示数据异常，调用方整体回滚）
     */
    public int addReceived(long ledgerId, long amountCents, Instant now) {
        return jdbc.update("""
                UPDATE t_reversal_ledger
                SET received_cents = received_cents + ?,
                    version_no = version_no + 1,
                    updated_at = ?
                WHERE id = ? AND received_cents + ? <= occupied_cents
                """,
                amountCents, Timestamp.from(now), ledgerId, amountCents);
    }

    private static final class LedgerRowMapper implements RowMapper<ReversalLedger> {
        @Override
        public ReversalLedger mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ReversalLedger(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getString("declaration_no"),
                    rs.getLong("frozen_cents"),
                    rs.getLong("occupied_cents"),
                    rs.getLong("received_cents"),
                    rs.getInt("version_no"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }
    }
}
