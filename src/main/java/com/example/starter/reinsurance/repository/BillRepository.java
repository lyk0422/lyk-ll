package com.example.starter.reinsurance.repository;

import com.example.starter.reinsurance.domain.Bill;
import com.example.starter.reinsurance.domain.BillStatus;
import com.example.starter.reinsurance.domain.BillType;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 账单仓储。并发确认/异议通过账单行级悲观锁串行化，保证唯一结果。
 */
@Repository
public class BillRepository {

    private static final RowMapper<Bill> MAPPER = (rs, n) -> {
        Bill bill = new Bill();
        bill.setId(rs.getLong("id"));
        bill.setBillNo(rs.getString("bill_no"));
        bill.setContractId(rs.getLong("contract_id"));
        bill.setPeriod(rs.getString("period"));
        bill.setBillType(BillType.valueOf(rs.getString("bill_type")));
        bill.setRefBillNo(rs.getString("ref_bill_no"));
        if (rs.wasNull()) {
            bill.setRefBillNo(null);
        }
        bill.setStatus(BillStatus.valueOf(rs.getString("status")));
        bill.setCedantConfirmed(rs.getBoolean("cedant_confirmed"));
        bill.setReinsurerConfirmed(rs.getBoolean("reinsurer_confirmed"));
        bill.setPremiumTotal(rs.getBigDecimal("premium_total"));
        bill.setRecoveryTotal(rs.getBigDecimal("recovery_total"));
        bill.setNetTotal(rs.getBigDecimal("net_total"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            bill.setCreatedAt(createdAt.toInstant());
        }
        Timestamp sealedAt = rs.getTimestamp("sealed_at");
        if (sealedAt != null) {
            bill.setSealedAt(sealedAt.toInstant());
        }
        return bill;
    };

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public BillRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public Bill insert(Bill bill) {
        java.time.Instant now = java.time.Instant.now(clock);
        bill.setCreatedAt(now);
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO bill (bill_no, contract_id, period, bill_type, ref_bill_no, status, "
                            + "cedant_confirmed, reinsurer_confirmed, premium_total, recovery_total, net_total, "
                            + "created_at, sealed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, bill.getBillNo());
            ps.setLong(2, bill.getContractId());
            ps.setString(3, bill.getPeriod());
            ps.setString(4, bill.getBillType().name());
            if (bill.getRefBillNo() == null) {
                ps.setNull(5, java.sql.Types.VARCHAR);
            } else {
                ps.setString(5, bill.getRefBillNo());
            }
            ps.setString(6, bill.getStatus().name());
            ps.setBoolean(7, bill.isCedantConfirmed());
            ps.setBoolean(8, bill.isReinsurerConfirmed());
            ps.setBigDecimal(9, bill.getPremiumTotal());
            ps.setBigDecimal(10, bill.getRecoveryTotal());
            ps.setBigDecimal(11, bill.getNetTotal());
            ps.setTimestamp(12, Timestamp.from(now));
            if (bill.getSealedAt() == null) {
                ps.setNull(13, java.sql.Types.TIMESTAMP);
            } else {
                ps.setTimestamp(13, Timestamp.from(bill.getSealedAt()));
            }
            return ps;
        }, keyHolder);
        bill.setId(keyHolder.getKeyAs(Long.class));
        return bill;
    }

    public Optional<Bill> findByBillNo(String billNo) {
        List<Bill> list = jdbcTemplate.query("SELECT * FROM bill WHERE bill_no = ?", MAPPER, billNo);
        return list.stream().findFirst();
    }

    public Bill getByBillNo(String billNo) {
        return findByBillNo(billNo)
                .orElseThrow(() -> new com.example.starter.reinsurance.common.ApiException(
                        com.example.starter.reinsurance.common.ErrorCode.NOT_FOUND,
                        "账单不存在: " + billNo));
    }

    /** 行级悲观锁读取账单（必须在事务内），串行化确认与异议。 */
    public Bill getByBillNoForUpdate(String billNo) {
        List<Bill> list =
                jdbcTemplate.query("SELECT * FROM bill WHERE bill_no = ? FOR UPDATE", MAPPER, billNo);
        return list.stream()
                .findFirst()
                .orElseThrow(() -> new com.example.starter.reinsurance.common.ApiException(
                        com.example.starter.reinsurance.common.ErrorCode.NOT_FOUND,
                        "账单不存在: " + billNo));
    }

    public Optional<Bill> findQuarterly(Long contractId, String period) {
        List<Bill> list = jdbcTemplate.query(
                "SELECT * FROM bill WHERE contract_id = ? AND period = ? AND bill_type = 'QUARTERLY'",
                MAPPER,
                contractId,
                period);
        return list.stream().findFirst();
    }

    /** 更新账单汇总金额（生成/调整时使用）。 */
    public int updateTotals(Long billId, java.math.BigDecimal premium, java.math.BigDecimal recovery) {
        return jdbcTemplate.update(
                "UPDATE bill SET premium_total = ?, recovery_total = ?, net_total = ? WHERE id = ?",
                premium,
                recovery,
                com.example.starter.reinsurance.common.Money.subtract(premium, recovery),
                billId);
    }

    /**
     * 再保人确认并封存：仅当账单未封存、未异议时生效；双方确认后写封存时间。
     *
     * @return 受影响行数；0 表示账单已被异议或已封存，本次确认失败
     */
    public int confirmAndSealIfPending(Long billId, java.time.Instant sealedAt) {
        return jdbcTemplate.update(
                "UPDATE bill SET reinsurer_confirmed = TRUE, status = 'SEALED', sealed_at = ? "
                        + "WHERE id = ? AND status = 'PENDING'",
                Timestamp.from(sealedAt),
                billId);
    }

    /**
     * 置为异议状态：仅待确认账单可异议。
     *
     * @return 受影响行数；0 表示账单已被确认封存或已异议
     */
    public int markDisputedIfPending(Long billId) {
        return jdbcTemplate.update(
                "UPDATE bill SET status = 'DISPUTED' WHERE id = ? AND status = 'PENDING'", billId);
    }
}
