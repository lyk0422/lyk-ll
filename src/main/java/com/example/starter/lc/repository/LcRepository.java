package com.example.starter.lc.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.lc.domain.LcAccount;

/**
 * 信用证账户数据访问。
 */
@Repository
public class LcRepository {

    private final JdbcTemplate jdbcTemplate;

    public LcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入合成信用证。编号重复由唯一约束抛出 DuplicateKeyException。 */
    public void insert(LcAccount a) {
        jdbcTemplate.update("""
                INSERT INTO lc_account
                  (lc_no, applicant, beneficiary, currency, amount_total, amount_available,
                   expiry_utc, created_at_utc, updated_at_utc)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                a.getLcNo(), a.getApplicant(), a.getBeneficiary(), a.getCurrency(),
                a.getAmountTotal(), a.getAmountAvailable(),
                a.getExpiryUtc() == null ? null : Timestamp.valueOf(a.getExpiryUtc()),
                Timestamp.valueOf(a.getCreatedAtUtc()), Timestamp.valueOf(a.getUpdatedAtUtc()));
    }

    /** 按信用证编号查询，不存在返回 null。 */
    public LcAccount findByLcNo(String lcNo) {
        List<LcAccount> list = jdbcTemplate.query(
                "SELECT id, lc_no, applicant, beneficiary, currency, amount_total, amount_available,"
                        + " expiry_utc, created_at_utc, updated_at_utc FROM lc_account WHERE lc_no = ?",
                ROW_MAPPER, lcNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 条件核减可用额度：仅当 {@code amount_available >= amount} 时整笔扣减。
     * 额度恰足时 compare 成立、扣减成功；不足则一行不更新（整笔拒绝）。
     *
     * @return 实际更新行数；1=扣减成功，0=额度不足
     */
    public int deductAvailable(String lcNo, BigDecimal amount) {
        return jdbcTemplate.update("""
                UPDATE lc_account
                   SET amount_available = amount_available - ?, updated_at_utc = CURRENT_TIMESTAMP
                 WHERE lc_no = ? AND amount_available >= ?
                """, amount, lcNo, amount);
    }

    private static final RowMapper<LcAccount> ROW_MAPPER = (rs, rowNum) -> {
        LcAccount a = new LcAccount();
        a.setId(rs.getLong("id"));
        a.setLcNo(rs.getString("lc_no"));
        a.setApplicant(rs.getString("applicant"));
        a.setBeneficiary(rs.getString("beneficiary"));
        a.setCurrency(rs.getString("currency"));
        a.setAmountTotal(rs.getBigDecimal("amount_total"));
        a.setAmountAvailable(rs.getBigDecimal("amount_available"));
        Timestamp expiry = rs.getTimestamp("expiry_utc");
        a.setExpiryUtc(expiry == null ? null : expiry.toLocalDateTime());
        a.setCreatedAtUtc(rs.getTimestamp("created_at_utc").toLocalDateTime());
        a.setUpdatedAtUtc(rs.getTimestamp("updated_at_utc").toLocalDateTime());
        return a;
    };
}
