package com.example.starter.irrigation.repo;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 临时加购水量单与按欠供比例分配明细仓储。
 */
@Repository
public class PurchaseRepository {

    /** 加购单行。 */
    public record PurchaseRow(long id, String requestId, String groupCode, long turnNo,
                              long amountCubicMeter, Instant createdAt) {
    }

    /** 加购分配明细行，单位立方米。 */
    public record AllocationRow(String outletCode, long shortageCubicMeter, long allocatedCubicMeter) {
    }

    private final JdbcTemplate jdbc;

    public PurchaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按幂等键查加购单。 */
    public Optional<PurchaseRow> findByRequestId(String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM irr_extra_purchase WHERE request_id = ?",
                    PURCHASE_MAPPER, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 按主键查加购单。 */
    public Optional<PurchaseRow> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM irr_extra_purchase WHERE id = ?", PURCHASE_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 加购单分配明细，按斗口编号升序。 */
    public List<AllocationRow> findAllocations(long purchaseId) {
        return jdbc.query("""
                SELECT outlet_code, shortage_cubic_meter, allocated_cubic_meter
                FROM irr_extra_allocation WHERE purchase_id = ? ORDER BY outlet_code
                """, ALLOC_MAPPER, purchaseId);
    }

    /** 插入加购单（调用方须在事务内），返回自增主键。 */
    public long insertPurchase(String requestId, String groupCode, long turnNo,
                               long amountCubicMeter, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO irr_extra_purchase
                        (request_id, group_code, turn_no, amount_cubic_meter, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, groupCode);
            ps.setLong(3, turnNo);
            ps.setLong(4, amountCubicMeter);
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入加购单未返回自增主键");
        }
        return key.longValue();
    }

    /** 插入加购分配明细（调用方须在事务内）。 */
    public void insertAllocation(long purchaseId, AllocationRow row) {
        jdbc.update("""
                INSERT INTO irr_extra_allocation
                    (purchase_id, outlet_code, shortage_cubic_meter, allocated_cubic_meter)
                VALUES (?, ?, ?, ?)
                """, purchaseId, row.outletCode(), row.shortageCubicMeter(), row.allocatedCubicMeter());
    }

    private static final RowMapper<PurchaseRow> PURCHASE_MAPPER = (ResultSet rs, int rowNum) -> new PurchaseRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("group_code"),
            rs.getLong("turn_no"),
            rs.getLong("amount_cubic_meter"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<AllocationRow> ALLOC_MAPPER = (ResultSet rs, int rowNum) -> new AllocationRow(
            rs.getString("outlet_code"),
            rs.getLong("shortage_cubic_meter"),
            rs.getLong("allocated_cubic_meter"));
}
