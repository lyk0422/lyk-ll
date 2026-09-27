package com.example.rebate.repo;

import com.example.rebate.domain.ChargebackBalance;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 结案后冲回余额数据访问。批准占用以条件更新为最终并发保障：
 * {@code frozen_cents - occupied_cents >= 本次金额} 才允许累加占用。
 */
@Repository
public class ChargebackBalanceRepository {

    private static final RowMapper<ChargebackBalance> MAPPER = new BalanceRowMapper();

    private final JdbcTemplate jdbc;

    public ChargebackBalanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 首单提交时建立余额行（批次＋报关单唯一）。 */
    public void insert(long batchId, String declarationNo, long frozenCents, Instant now) {
        jdbc.update("""
                INSERT INTO t_chargeback_balance
                    (batch_id, declaration_no, frozen_cents, occupied_cents, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, ?)
                """,
                batchId, declarationNo, frozenCents, Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<ChargebackBalance> find(long batchId, String declarationNo) {
        List<ChargebackBalance> list = jdbc.query(
                BASE_SELECT + " WHERE batch_id = ? AND declaration_no = ?",
                MAPPER, batchId, declarationNo);
        return list.stream().findFirst();
    }

    public List<ChargebackBalance> findByBatchId(long batchId) {
        return jdbc.query(BASE_SELECT + " WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    /**
     * 条件占用：仅当累计占用不超过冻结额时累加。依赖行锁串行化并发批准，超占返回 0 行。
     *
     * @return 受影响行数；0 表示剩余可冲额不足或余额行不存在
     */
    public int tryOccupy(long batchId, String declarationNo, long amountCents, Instant now) {
        return jdbc.update("""
                UPDATE t_chargeback_balance
                SET occupied_cents = occupied_cents + ?, updated_at = ?
                WHERE batch_id = ? AND declaration_no = ?
                  AND occupied_cents + ? <= frozen_cents
                """,
                amountCents, Timestamp.from(now), batchId, declarationNo, amountCents);
    }

    private static final String BASE_SELECT =
            "SELECT id, batch_id, declaration_no, frozen_cents, occupied_cents, created_at, updated_at "
                    + "FROM t_chargeback_balance";

    private static final class BalanceRowMapper implements RowMapper<ChargebackBalance> {
        @Override
        public ChargebackBalance mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ChargebackBalance(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getString("declaration_no"),
                    rs.getLong("frozen_cents"),
                    rs.getLong("occupied_cents"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }
    }
}
