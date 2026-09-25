package com.example.starter.reinsurance.repository;

import com.example.starter.reinsurance.domain.BillDispute;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 账单异议仓储。
 */
@Repository
public class BillDisputeRepository {

    private static final RowMapper<BillDispute> MAPPER = (rs, n) -> {
        BillDispute dispute = new BillDispute();
        dispute.setId(rs.getLong("id"));
        dispute.setBillId(rs.getLong("bill_id"));
        dispute.setItemRef(rs.getString("item_ref"));
        dispute.setExpectedAmount(rs.getBigDecimal("expected_amount"));
        dispute.setReason(rs.getString("reason"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            dispute.setCreatedAt(createdAt.toInstant());
        }
        return dispute;
    };

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public BillDisputeRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public void insert(BillDispute dispute) {
        java.time.Instant now = java.time.Instant.now(clock);
        dispute.setCreatedAt(now);
        jdbcTemplate.update(
                "INSERT INTO bill_dispute (bill_id, item_ref, expected_amount, reason, created_at) "
                        + "VALUES (?,?,?,?,?)",
                dispute.getBillId(),
                dispute.getItemRef(),
                dispute.getExpectedAmount(),
                dispute.getReason(),
                Timestamp.from(now));
    }

    public List<BillDispute> findByBillId(Long billId) {
        return jdbcTemplate.query(
                "SELECT * FROM bill_dispute WHERE bill_id = ? ORDER BY id", MAPPER, billId);
    }
}
