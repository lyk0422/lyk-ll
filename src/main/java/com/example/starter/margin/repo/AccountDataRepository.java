package com.example.starter.margin.repo;

import com.example.starter.margin.domain.LiabilityStatus;
import com.example.starter.margin.po.AccountPo;
import com.example.starter.margin.po.LiabilityPo;
import com.example.starter.margin.po.SnapshotPo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 账户、负债、持仓与已确认快照的数据访问。所有金额单位元、时间存 UTC。
 */
@Repository
public class AccountDataRepository {

    private final JdbcTemplate jdbc;

    public AccountDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AccountPo> ACCOUNT_MAPPER = (rs, n) -> new AccountPo(
            rs.getString("account_no"),
            rs.getString("client_name"),
            rs.getString("base_currency"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<LiabilityPo> LIABILITY_MAPPER = (rs, n) -> {
        Timestamp repaidAt = rs.getTimestamp("repaid_at");
        return new LiabilityPo(
                rs.getLong("id"),
                rs.getString("account_no"),
                rs.getString("liability_no"),
                rs.getString("currency"),
                rs.getBigDecimal("outstanding_amount"),
                LiabilityStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                repaidAt == null ? null : repaidAt.toInstant());
    };

    private static final RowMapper<SnapshotPo> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotPo(
            rs.getLong("id"),
            rs.getString("account_no"),
            rs.getBigDecimal("total_assets"),
            rs.getBigDecimal("total_liabilities"),
            rs.getBoolean("confirmed"),
            rs.getTimestamp("market_time").toInstant(),
            rs.getString("remark"),
            rs.getTimestamp("created_at").toInstant());

    public void insertAccount(AccountPo account) {
        jdbc.update("INSERT INTO account (account_no, client_name, base_currency, created_at) VALUES (?,?,?,?)",
                account.accountNo(), account.clientName(), account.baseCurrency(),
                Timestamp.from(account.createdAt()));
    }

    public AccountPo findAccount(String accountNo) {
        List<AccountPo> list = jdbc.query(
                "SELECT account_no, client_name, base_currency, created_at FROM account WHERE account_no = ?",
                ACCOUNT_MAPPER, accountNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 账户级悲观锁：入账与强平事务内首先锁定同一行，串行化竞态。
     */
    public void lockAccount(String accountNo) {
        jdbc.queryForList("SELECT account_no FROM account WHERE account_no = ? FOR UPDATE", accountNo);
    }

    public void insertHolding(String accountNo, String symbol, long quantity,
                              BigDecimal lastPrice, BigDecimal marketValue, Instant snapshotTime) {
        jdbc.update("INSERT INTO holding (account_no, symbol, quantity, last_price, market_value, snapshot_time) "
                        + "VALUES (?,?,?,?,?,?)",
                accountNo, symbol, quantity, lastPrice, marketValue, Timestamp.from(snapshotTime));
    }

    public void insertLiability(LiabilityPo liability) {
        jdbc.update("INSERT INTO liability (account_no, liability_no, currency, outstanding_amount, "
                        + "status, created_at, repaid_at) VALUES (?,?,?,?,?,?,?)",
                liability.accountNo(), liability.liabilityNo(), liability.currency(),
                liability.outstandingAmount(), liability.status().name(),
                Timestamp.from(liability.createdAt()),
                liability.repaidAt() == null ? null : Timestamp.from(liability.repaidAt()));
    }

    /** 未偿负债，按金额从大到小排序（强平顺序）；同额按编号稳定排序。 */
    public List<LiabilityPo> findOutstandingLiabilities(String accountNo) {
        return jdbc.query("SELECT id, account_no, liability_no, currency, outstanding_amount, status, "
                        + "created_at, repaid_at FROM liability WHERE account_no = ? AND status = 'OUTSTANDING' "
                        + "ORDER BY outstanding_amount DESC, id ASC",
                LIABILITY_MAPPER, accountNo);
    }

    /** 强平处置：部分/全额偿还一笔负债。全额置 REPAID，部分仅减少未偿金额。 */
    public void repayLiability(String accountNo, String liabilityNo, BigDecimal repayAmount, Instant repaidAt) {
        int updated = jdbc.update(
                "UPDATE liability SET outstanding_amount = outstanding_amount - ?, "
                        + "status = CASE WHEN outstanding_amount - ? <= 0 THEN 'REPAID' ELSE status END, "
                        + "repaid_at = CASE WHEN outstanding_amount - ? <= 0 THEN ? ELSE repaid_at END "
                        + "WHERE account_no = ? AND liability_no = ? AND status = 'OUTSTANDING'",
                repayAmount, repayAmount, repayAmount, Timestamp.from(repaidAt), accountNo, liabilityNo);
        if (updated != 1) {
            throw new IllegalStateException("强平偿还负债失败或负债已结清: " + accountNo + "/" + liabilityNo);
        }
    }

    public void insertSnapshot(SnapshotPo snapshot) {
        jdbc.update("INSERT INTO account_snapshot (account_no, total_assets, total_liabilities, confirmed, "
                        + "market_time, remark, created_at) VALUES (?,?,?,?,?,?,?)",
                snapshot.accountNo(), snapshot.totalAssets(), snapshot.totalLiabilities(),
                snapshot.confirmed(), Timestamp.from(snapshot.marketTime()),
                snapshot.remark(), Timestamp.from(snapshot.createdAt()));
    }

    /** 账户最新一条已确认快照；不存在返回 null。比例判定一律以此为准。 */
    public SnapshotPo findLatestConfirmedSnapshot(String accountNo) {
        List<SnapshotPo> list = jdbc.query(
                "SELECT id, account_no, total_assets, total_liabilities, confirmed, market_time, remark, created_at "
                        + "FROM account_snapshot WHERE account_no = ? AND confirmed = TRUE "
                        + "ORDER BY id DESC LIMIT 1",
                SNAPSHOT_MAPPER, accountNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 测试清理：清空全部业务表（按外览无显式外键，顺序仍保守处理）。 */
    @Transactional
    public void deleteAll() {
        jdbc.update("DELETE FROM liquidation_item");
        jdbc.update("DELETE FROM liquidation_job");
        jdbc.update("DELETE FROM margin_deposit");
        jdbc.update("DELETE FROM margin_notice");
        jdbc.update("DELETE FROM account_snapshot");
        jdbc.update("DELETE FROM liability");
        jdbc.update("DELETE FROM holding");
        jdbc.update("DELETE FROM account");
        jdbc.update("DELETE FROM trade_holiday");
    }
}
