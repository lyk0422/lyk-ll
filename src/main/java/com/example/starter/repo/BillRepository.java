package com.example.starter.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.Models.Bill;
import com.example.starter.domain.Models.BillItem;
import com.example.starter.domain.Models.BillStatus;
import com.example.starter.domain.Models.BillType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 账单、账单明细与异议数据访问。状态流转通过行锁（SELECT ... FOR UPDATE）保证并发唯一结果。
 */
@Repository
public class BillRepository {

    private static final RowMapper<Bill> BILL_MAPPER = (rs, n) -> mapBill(rs);
    private static final RowMapper<BillItem> ITEM_MAPPER = (rs, n) -> mapItem(rs);

    private final JdbcTemplate jdbc;

    public BillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Bill mapBill(ResultSet rs) throws SQLException {
        return new Bill(rs.getLong("id"), rs.getString("bill_no"), rs.getLong("treaty_id"),
                rs.getString("quarter_label"), BillType.valueOf(rs.getString("bill_type")),
                rs.getString("original_bill_no"), BillStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("total_premium_cession"), rs.getBigDecimal("total_claim_recovery"),
                rs.getBigDecimal("total_amount"), rs.getBoolean("confirmed_by_cedant"),
                rs.getBoolean("confirmed_by_reinsurer"), rs.getString("reason"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("sealed_at", OffsetDateTime.class));
    }

    private static BillItem mapItem(ResultSet rs) throws SQLException {
        return new BillItem(rs.getLong("id"), rs.getLong("bill_id"), rs.getLong("policy_id"),
                rs.getBigDecimal("premium_cession"), rs.getBigDecimal("claim_recovery"),
                rs.getBigDecimal("item_total"), rs.getBigDecimal("retained_amount"),
                rs.getBoolean("limit_applied"));
    }

    public Bill insertBill(String billNo, String dedupeKey, long treatyId, String quarterLabel,
                           BillType billType, String originalBillNo, BillStatus status,
                           BigDecimal totalPremiumCession, BigDecimal totalClaimRecovery,
                           BigDecimal totalAmount, String reason, OffsetDateTime now) {
        jdbc.update("INSERT INTO bill (bill_no, dedupe_key, treaty_id, quarter_label, bill_type,"
                        + " original_bill_no, status, total_premium_cession, total_claim_recovery,"
                        + " total_amount, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                billNo, dedupeKey, treatyId, quarterLabel, billType.name(), originalBillNo,
                status.name(), totalPremiumCession, totalClaimRecovery, totalAmount, reason, now);
        return findByBillNo(billNo).orElseThrow();
    }

    public void insertItem(long billId, long policyId, BigDecimal premiumCession,
                           BigDecimal claimRecovery, BigDecimal itemTotal, BigDecimal retainedAmount,
                           boolean limitApplied) {
        jdbc.update("INSERT INTO bill_item (bill_id, policy_id, premium_cession, claim_recovery,"
                        + " item_total, retained_amount, limit_applied) VALUES (?, ?, ?, ?, ?, ?, ?)",
                billId, policyId, premiumCession, claimRecovery, itemTotal, retainedAmount, limitApplied);
    }

    public Optional<Bill> findByBillNo(String billNo) {
        return jdbc.query("SELECT * FROM bill WHERE bill_no = ?", BILL_MAPPER, billNo)
                .stream().findFirst();
    }

    public Optional<Bill> findByDedupeKey(String dedupeKey) {
        return jdbc.query("SELECT * FROM bill WHERE dedupe_key = ?", BILL_MAPPER, dedupeKey)
                .stream().findFirst();
    }

    /** 出账后按主键回填可读账单号（如 B00000001）。 */
    public void updateBillNo(long billId, String billNo) {
        jdbc.update("UPDATE bill SET bill_no = ? WHERE id = ?", billNo, billId);
    }

    /** 加行锁读取账单，用于确认/异议/封存的状态流转，保证并发下唯一结果。 */
    public Optional<Bill> findByBillNoForUpdate(String billNo) {
        return jdbc.query("SELECT * FROM bill WHERE bill_no = ? FOR UPDATE", BILL_MAPPER, billNo)
                .stream().findFirst();
    }

    public List<BillItem> findItems(long billId) {
        return jdbc.query("SELECT * FROM bill_item WHERE bill_id = ? ORDER BY id", ITEM_MAPPER, billId);
    }

    public void updateConfirmation(long billId, boolean confirmedByCedant, boolean confirmedByReinsurer,
                                   BillStatus status, OffsetDateTime sealedAt) {
        jdbc.update("UPDATE bill SET confirmed_by_cedant = ?, confirmed_by_reinsurer = ?,"
                        + " status = ?, sealed_at = ? WHERE id = ?",
                confirmedByCedant, confirmedByReinsurer, status.name(), sealedAt, billId);
    }

    public void updateStatus(long billId, BillStatus status) {
        jdbc.update("UPDATE bill SET status = ? WHERE id = ?", status.name(), billId);
    }

    public long insertDispute(long billId, String requestId, OffsetDateTime now) {
        jdbc.update("INSERT INTO bill_dispute (bill_id, request_id, created_at) VALUES (?, ?, ?)",
                billId, requestId, now);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM bill_dispute WHERE bill_id = ? AND request_id = ?",
                Long.class, billId, requestId);
        return id == null ? -1 : id;
    }

    public void insertDisputeItem(long disputeId, long billItemId, String reason) {
        jdbc.update("INSERT INTO bill_dispute_item (dispute_id, bill_item_id, reason) VALUES (?, ?, ?)",
                disputeId, billItemId, reason);
    }

    public boolean itemBelongsToBill(long billItemId, long billId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM bill_item WHERE id = ? AND bill_id = ?",
                Long.class, billItemId, billId);
        return count != null && count > 0;
    }

    public long countBills() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM bill", Long.class);
        return count == null ? 0 : count;
    }
}
