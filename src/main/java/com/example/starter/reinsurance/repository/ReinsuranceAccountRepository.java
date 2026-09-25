package com.example.starter.reinsurance.repository;

import com.example.starter.reinsurance.common.ApiException;
import com.example.starter.reinsurance.common.ErrorCode;
import com.example.starter.reinsurance.common.Money;
import com.example.starter.reinsurance.domain.ReinsuranceAccount;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合约摊回账户仓储。账户余额任何时刻不得为负：
 * 借记（冲销）采用条件更新 {@code balance + ? >= 0}，并由数据库 CHECK 约束双重兜底。
 */
@Repository
public class ReinsuranceAccountRepository {

    private static final RowMapper<ReinsuranceAccount> MAPPER = (rs, n) -> {
        ReinsuranceAccount account = new ReinsuranceAccount();
        account.setId(rs.getLong("id"));
        account.setContractId(rs.getLong("contract_id"));
        account.setBalance(rs.getBigDecimal("balance"));
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) {
            account.setUpdatedAt(updatedAt.toInstant());
        }
        return account;
    };

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ReinsuranceAccountRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** 开户（幂等：合约唯一约束冲突时取已有账户）。 */
    @Transactional
    public ReinsuranceAccount getOrCreate(Long contractId) {
        List<ReinsuranceAccount> existing =
                jdbcTemplate.query("SELECT * FROM reinsurance_account WHERE contract_id = ?", MAPPER, contractId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Instant now = Instant.now(clock);
        try {
            jdbcTemplate.update(
                    "INSERT INTO reinsurance_account (contract_id, balance, updated_at) VALUES (?, 0, ?)",
                    contractId,
                    Timestamp.from(now));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM reinsurance_account WHERE contract_id = ?", MAPPER, contractId);
        }
        return jdbcTemplate.queryForObject(
                "SELECT * FROM reinsurance_account WHERE contract_id = ?", MAPPER, contractId);
    }

    /** 行级悲观锁读取账户（必须在事务内），串行化同一合约的预提与冲销。 */
    public ReinsuranceAccount lockByContract(Long contractId) {
        getOrCreate(contractId);
        List<ReinsuranceAccount> list = jdbcTemplate.query(
                "SELECT * FROM reinsurance_account WHERE contract_id = ? FOR UPDATE", MAPPER, contractId);
        return list.get(0);
    }

    /**
     * 条件贷记/借记：delta 为正表示预提入账，为负表示结案冲销。
     * 仅当变动后余额非负时更新成功；否则返回 {@code false}（余额非负业务断言失败）。
     */
    public boolean applyDelta(Long contractId, BigDecimal delta) {
        BigDecimal rounded = Money.amount(delta);
        int rows;
        if (rounded.signum() >= 0) {
            rows = jdbcTemplate.update(
                    "UPDATE reinsurance_account SET balance = balance + ?, updated_at = ? "
                            + "WHERE contract_id = ?",
                    rounded,
                    Timestamp.from(Instant.now(clock)),
                    contractId);
        } else {
            rows = jdbcTemplate.update(
                    "UPDATE reinsurance_account SET balance = balance + ?, updated_at = ? "
                            + "WHERE contract_id = ? AND balance + ? >= 0",
                    rounded,
                    Timestamp.from(Instant.now(clock)),
                    contractId,
                    rounded);
        }
        if (rows == 0) {
            return false;
        }
        ReinsuranceAccount after = jdbcTemplate.queryForObject(
                "SELECT * FROM reinsurance_account WHERE contract_id = ?", MAPPER, contractId);
        if (after == null || after.getBalance().signum() < 0) {
            throw new ApiException(
                    ErrorCode.INSUFFICIENT_BALANCE,
                    "摊回账户余额不得为负，contractId=" + contractId);
        }
        return true;
    }

    public ReinsuranceAccount getByContract(Long contractId) {
        return jdbcTemplate.queryForObject(
                "SELECT * FROM reinsurance_account WHERE contract_id = ?", MAPPER, contractId);
    }
}
