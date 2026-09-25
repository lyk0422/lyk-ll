package com.example.starter.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.Models.AccountEntry;
import com.example.starter.domain.Models.EntryType;
import com.example.starter.domain.Models.RecoveryAccount;
import com.example.starter.error.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 摊回账户及分录数据访问。余额变动使用带非负守卫的原子 UPDATE，并发下串行化于账户行锁。
 */
@Repository
public class AccountRepository {

    private static final RowMapper<RecoveryAccount> ACCOUNT_MAPPER = (rs, n) -> mapAccount(rs);
    private static final RowMapper<AccountEntry> ENTRY_MAPPER = (rs, n) -> mapEntry(rs);

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static RecoveryAccount mapAccount(ResultSet rs) throws SQLException {
        return new RecoveryAccount(rs.getLong("treaty_id"), rs.getBigDecimal("balance"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static AccountEntry mapEntry(ResultSet rs) throws SQLException {
        return new AccountEntry(rs.getLong("id"), rs.getLong("treaty_id"), rs.getLong("claim_id"),
                EntryType.valueOf(rs.getString("entry_type")), rs.getBigDecimal("amount"),
                rs.getBigDecimal("balance_after"), rs.getString("request_id"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    public void createAccountIfAbsent(long treatyId, OffsetDateTime now) {
        jdbc.update("MERGE INTO recovery_account (treaty_id, balance, updated_at)"
                        + " KEY (treaty_id) VALUES (?, 0, ?)",
                treatyId, now);
    }

    public Optional<RecoveryAccount> findByTreatyId(long treatyId) {
        return jdbc.query("SELECT * FROM recovery_account WHERE treaty_id = ?", ACCOUNT_MAPPER, treatyId)
                .stream().findFirst();
    }

    /**
     * 原子地变动余额并保证非负：delta 为带符号金额。
     * 余额不足时抛出冲突异常，触发当前事务回滚（预提与冲销的原子性由此保证）。
     *
     * @return 变动后的余额
     */
    public BigDecimal applyDelta(long treatyId, BigDecimal delta, OffsetDateTime now) {
        int updated = jdbc.update("UPDATE recovery_account SET balance = balance + ?, updated_at = ?"
                        + " WHERE treaty_id = ? AND balance + ? >= 0",
                delta, now, treatyId, delta);
        if (updated == 0) {
            throw ApiException.conflict("NEGATIVE_BALANCE",
                    "摊回账户余额不足，操作将使余额为负，已回滚: treatyId=" + treatyId);
        }
        return findByTreatyId(treatyId).orElseThrow().balance();
    }

    public void insertEntry(long treatyId, long claimId, EntryType type, BigDecimal amount,
                            BigDecimal balanceAfter, String requestId, OffsetDateTime now) {
        jdbc.update("INSERT INTO account_entry (treaty_id, claim_id, entry_type, amount, balance_after,"
                        + " request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                treatyId, claimId, type.name(), amount, balanceAfter, requestId, now);
    }

    public List<AccountEntry> findEntriesByTreatyId(long treatyId) {
        return jdbc.query("SELECT * FROM account_entry WHERE treaty_id = ? ORDER BY id",
                ENTRY_MAPPER, treatyId);
    }

    public List<AccountEntry> findEntriesByClaimId(long claimId) {
        return jdbc.query("SELECT * FROM account_entry WHERE claim_id = ? ORDER BY id",
                ENTRY_MAPPER, claimId);
    }
}
