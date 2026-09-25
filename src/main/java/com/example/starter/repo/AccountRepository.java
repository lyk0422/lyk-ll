package com.example.starter.repo;

import com.example.starter.error.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Objects;

/**
 * 信用账户数据访问。行级悲观锁（SELECT ... FOR UPDATE）用于串行化同一账户的
 * 快照评估、保证金入账与强平执行，保证“先到达者决定结果”。
 */
@Repository
public class AccountRepository {

    /** 账户当前状态行。金额单位：分。 */
    public record AccountRow(long id, String accountNo, String currency,
                             long totalAssets, long totalLiabilities, String status) {
    }

    private static final RowMapper<AccountRow> MAPPER = (rs, i) -> new AccountRow(
            rs.getLong("id"),
            rs.getString("account_no"),
            rs.getString("currency"),
            rs.getLong("total_assets"),
            rs.getLong("total_liabilities"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String accountNo, String currency) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO account (account_no, currency) VALUES (?, ?)",
                    new String[]{"id"});
            ps.setString(1, accountNo);
            ps.setString(2, currency);
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    public AccountRow findById(long id) {
        return jdbc.query("SELECT * FROM account WHERE id = ?", MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("账户不存在: " + id));
    }

    /** 加行级写锁读取账户，须在事务内调用；不存在时抛 404。 */
    public AccountRow lockById(long id) {
        return jdbc.query("SELECT * FROM account WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("账户不存在: " + id));
    }

    public void updateTotals(long id, long totalAssets, long totalLiabilities) {
        jdbc.update("UPDATE account SET total_assets = ?, total_liabilities = ? WHERE id = ?",
                totalAssets, totalLiabilities, id);
    }

    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE account SET status = ? WHERE id = ?", status, id);
    }
}
