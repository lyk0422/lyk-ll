package com.example.starter.pension.repo;

import com.example.starter.pension.po.InheritanceLedgerRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 继承台账数据访问：勾稽明细条目由 (claim_id, entry_type, ref_id) 唯一索引防重。
 */
@Repository
public class InheritanceLedgerRepository {

    /** 账户余额构成条目（ref_id 固定 0）。 */
    public static final String ENTRY_ACCOUNT_BALANCE = "ACCOUNT_BALANCE";
    /** 未付分期本金构成条目（ref_id 为期次 ID）。 */
    public static final String ENTRY_UNPAID_INSTALLMENT = "UNPAID_INSTALLMENT";
    /** 继承支付出款条目（ref_id 固定 0）。 */
    public static final String ENTRY_INHERITANCE_PAYOUT = "INHERITANCE_PAYOUT";

    private static final RowMapper<InheritanceLedgerRow> ROW_MAPPER = (rs, rowNum) -> new InheritanceLedgerRow(
            rs.getLong("id"),
            rs.getLong("claim_id"),
            rs.getString("entry_type"),
            rs.getLong("ref_id"),
            rs.getLong("amount_cents"),
            rs.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC));

    private final JdbcClient jdbcClient;

    public InheritanceLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 登记一条台账勾稽明细；重复条目由唯一索引拒绝。
     *
     * @return 受影响行数
     */
    public int insert(long claimId, String entryType, long refId, long amountCents) {
        return jdbcClient.sql("""
                        INSERT INTO inheritance_ledger (claim_id, entry_type, ref_id, amount_cents)
                        VALUES (:claimId, :entryType, :refId, :amountCents)""")
                .param("claimId", claimId)
                .param("entryType", entryType)
                .param("refId", refId)
                .param("amountCents", amountCents)
                .update();
    }

    /** 查询申领的全部台账明细，按主键升序（可复算勾稽）。 */
    public List<InheritanceLedgerRow> findByClaimId(long claimId) {
        return jdbcClient.sql("""
                        SELECT id, claim_id, entry_type, ref_id, amount_cents, created_at
                          FROM inheritance_ledger
                         WHERE claim_id = :claimId
                         ORDER BY id""")
                .param("claimId", claimId)
                .query(ROW_MAPPER)
                .list();
    }
}
