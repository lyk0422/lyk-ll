package com.example.starter.deicing.db;

import com.example.starter.deicing.domain.BorrowStatus;
import com.example.starter.deicing.domain.ReservationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 除冰液库存数据访问：配比表、入库批次、预占、消耗台账与跨班组借用。
 */
@Repository
public class InventoryRepository {

    /**
     * 配比表行：单位作业消耗（升/车位占用分钟）。
     */
    public record MixtureRatio(String fluidCode, int litersPerMinute) {
    }

    private static final RowMapper<FluidBatchPo> BATCH_MAPPER = (ResultSet rs, int rowNum) -> {
        FluidBatchPo po = new FluidBatchPo();
        po.setId(rs.getLong("id"));
        po.setBatchNo(rs.getString("batch_no"));
        po.setFluidCode(rs.getString("fluid_code"));
        po.setQuantityIn(rs.getInt("quantity_in"));
        po.setQuantityAvailable(rs.getInt("quantity_available"));
        po.setExpiryTime(RowMappers.instant(rs, "expiry_time"));
        po.setCreatedAt(RowMappers.instant(rs, "created_at"));
        return po;
    };

    private static final RowMapper<InventoryReservationPo> RESERVATION_MAPPER =
            (ResultSet rs, int rowNum) -> {
                InventoryReservationPo po = new InventoryReservationPo();
                po.setId(rs.getLong("id"));
                po.setRequestId(rs.getString("request_id"));
                po.setFluidCode(rs.getString("fluid_code"));
                po.setQuantity(rs.getInt("quantity"));
                po.setStatus(ReservationStatus.valueOf(rs.getString("status")));
                po.setCreatedAt(RowMappers.instant(rs, "created_at"));
                po.setUpdatedAt(RowMappers.instant(rs, "updated_at"));
                return po;
            };

    private static final RowMapper<BorrowOrderPo> BORROW_MAPPER = (ResultSet rs, int rowNum) -> {
        BorrowOrderPo po = new BorrowOrderPo();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setTankId(rs.getString("tank_id"));
        po.setFluidCode(rs.getString("fluid_code"));
        po.setQuantity(rs.getInt("quantity"));
        po.setFromTeam(rs.getString("from_team"));
        po.setToTeam(rs.getString("to_team"));
        po.setStatus(BorrowStatus.valueOf(rs.getString("status")));
        po.setFingerprint(rs.getString("fingerprint"));
        po.setCreatedAt(RowMappers.instant(rs, "created_at"));
        po.setReturnedAt(RowMappers.instant(rs, "returned_at"));
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    public InventoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---- 配比表 --------------------------------------------------------------

    /**
     * 查询指定除冰工艺的合成配比（单位：升/车位占用分钟），按除冰液编码排序保证稳定。
     */
    public List<MixtureRatio> findMixture(String deicingType) {
        return jdbcTemplate.query("""
                        SELECT fluid_code, liters_per_minute FROM fluid_mixture
                        WHERE deicing_type = ? ORDER BY fluid_code
                        """,
                (rs, rowNum) -> new MixtureRatio(rs.getString("fluid_code"),
                        rs.getInt("liters_per_minute")),
                deicingType);
    }

    public boolean fluidExists(String fluidCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fluid_type WHERE fluid_code = ?", Integer.class, fluidCode);
        return count != null && count > 0;
    }

    public List<String> findAllFluidCodes() {
        return jdbcTemplate.queryForList(
                "SELECT fluid_code FROM fluid_type ORDER BY fluid_code", String.class);
    }

    // ---- 入库批次 ------------------------------------------------------------

    public void insertBatch(FluidBatchPo po) {
        jdbcTemplate.update("""
                        INSERT INTO fluid_batch
                            (batch_no, fluid_code, quantity_in, quantity_available, expiry_time, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                po.getBatchNo(), po.getFluidCode(), po.getQuantityIn(), po.getQuantityAvailable(),
                Timestamp.from(po.getExpiryTime()), Timestamp.from(po.getCreatedAt()));
    }

    public FluidBatchPo findBatchByBatchNo(String batchNo) {
        List<FluidBatchPo> list = jdbcTemplate.query("""
                        SELECT id, batch_no, fluid_code, quantity_in, quantity_available,
                               expiry_time, created_at
                        FROM fluid_batch WHERE batch_no = ?
                        """,
                BATCH_MAPPER, batchNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 可参与预占/结算的批次：未到期且剩余量大于 0，按效期升序（先到期先出）。
     */
    public List<FluidBatchPo> findConsumableBatches(String fluidCode, Instant now) {
        return jdbcTemplate.query("""
                        SELECT id, batch_no, fluid_code, quantity_in, quantity_available,
                               expiry_time, created_at
                        FROM fluid_batch
                        WHERE fluid_code = ? AND quantity_available > 0 AND expiry_time > ?
                        ORDER BY expiry_time, id
                        """,
                BATCH_MAPPER, fluidCode, Timestamp.from(now));
    }

    /**
     * 未到期批次剩余量合计（升）。
     */
    public int sumAvailableNonExpired(String fluidCode, Instant now) {
        return sum("""
                SELECT COALESCE(SUM(quantity_available), 0) FROM fluid_batch
                WHERE fluid_code = ? AND expiry_time > ?
                """, fluidCode, Timestamp.from(now));
    }

    /**
     * 已效期耗尽批次的剩余量合计（升），用于不足提示。
     */
    public int sumExpiredAvailable(String fluidCode, Instant now) {
        return sum("""
                SELECT COALESCE(SUM(quantity_available), 0) FROM fluid_batch
                WHERE fluid_code = ? AND expiry_time <= ?
                """, fluidCode, Timestamp.from(now));
    }

    /**
     * 批次扣减（FEFO 结算用），带剩余量下界保护；返回更新行数。
     */
    public int decreaseBatch(long batchId, int quantity) {
        return jdbcTemplate.update("""
                        UPDATE fluid_batch SET quantity_available = quantity_available - ?
                        WHERE id = ? AND quantity_available >= ?
                        """,
                quantity, batchId, quantity);
    }

    // ---- 预占 ----------------------------------------------------------------

    public void insertReservation(InventoryReservationPo po) {
        jdbcTemplate.update("""
                        INSERT INTO inventory_reservation
                            (request_id, fluid_code, quantity, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                po.getRequestId(), po.getFluidCode(), po.getQuantity(), po.getStatus().name(),
                Timestamp.from(po.getCreatedAt()), Timestamp.from(po.getUpdatedAt()));
    }

    public List<InventoryReservationPo> findReservationsByRequest(String requestId) {
        return jdbcTemplate.query("""
                        SELECT id, request_id, fluid_code, quantity, status, created_at, updated_at
                        FROM inventory_reservation WHERE request_id = ? ORDER BY fluid_code
                        """,
                RESERVATION_MAPPER, requestId);
    }

    public List<InventoryReservationPo> findActiveReservationsByRequest(String requestId) {
        return jdbcTemplate.query("""
                        SELECT id, request_id, fluid_code, quantity, status, created_at, updated_at
                        FROM inventory_reservation
                        WHERE request_id = ? AND status = 'ACTIVE' ORDER BY fluid_code
                        """,
                RESERVATION_MAPPER, requestId);
    }

    public void updateReservationStatus(long id, ReservationStatus status, Instant updatedAt) {
        jdbcTemplate.update(
                "UPDATE inventory_reservation SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), Timestamp.from(updatedAt), id);
    }

    public int sumActiveReservation(String fluidCode) {
        return sum("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory_reservation
                WHERE fluid_code = ? AND status = 'ACTIVE'
                """, fluidCode);
    }

    // ---- 消耗台账 ------------------------------------------------------------

    public void insertConsumption(String requestId, String fluidCode, long batchId, int quantity,
                                  Instant now) {
        jdbcTemplate.update("""
                        INSERT INTO inventory_consumption
                            (request_id, fluid_code, batch_id, quantity, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                requestId, fluidCode, batchId, quantity, Timestamp.from(now));
    }

    public int sumConsumption(String fluidCode) {
        return sum("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory_consumption WHERE fluid_code = ?
                """, fluidCode);
    }

    public int sumInbound(String fluidCode) {
        return sum("""
                SELECT COALESCE(SUM(quantity_in), 0) FROM fluid_batch WHERE fluid_code = ?
                """, fluidCode);
    }

    // ---- 借用单 --------------------------------------------------------------

    public void insertBorrow(BorrowOrderPo po) {
        jdbcTemplate.update("""
                        INSERT INTO borrow_order
                            (request_id, tank_id, fluid_code, quantity, from_team, to_team,
                             status, fingerprint, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                po.getRequestId(), po.getTankId(), po.getFluidCode(), po.getQuantity(),
                po.getFromTeam(), po.getToTeam(), po.getStatus().name(), po.getFingerprint(),
                Timestamp.from(po.getCreatedAt()));
    }

    public BorrowOrderPo findBorrowByRequestId(String requestId) {
        List<BorrowOrderPo> list = jdbcTemplate.query("""
                        SELECT id, request_id, tank_id, fluid_code, quantity, from_team, to_team,
                               status, fingerprint, created_at, returned_at
                        FROM borrow_order WHERE request_id = ?
                        """,
                BORROW_MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void markBorrowReturned(String requestId, Instant returnedAt) {
        jdbcTemplate.update("""
                        UPDATE borrow_order SET status = 'RETURNED', returned_at = ?
                        WHERE request_id = ?
                        """,
                Timestamp.from(returnedAt), requestId);
    }

    public int sumOpenBorrow(String fluidCode) {
        return sum("""
                SELECT COALESCE(SUM(quantity), 0) FROM borrow_order
                WHERE fluid_code = ? AND status = 'OPEN'
                """, fluidCode);
    }

    // ---- 液罐未归还占位 --------------------------------------------------------

    public void insertTankOpen(String tankId, String requestId, Instant now) {
        jdbcTemplate.update("""
                        INSERT INTO tank_borrow_open (tank_id, request_id, created_at)
                        VALUES (?, ?, ?)
                        """,
                tankId, requestId, Timestamp.from(now));
    }

    public boolean isTankOpen(String tankId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tank_borrow_open WHERE tank_id = ?", Integer.class, tankId);
        return count != null && count > 0;
    }

    public void deleteTankOpen(String tankId) {
        jdbcTemplate.update("DELETE FROM tank_borrow_open WHERE tank_id = ?", tankId);
    }

    private int sum(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
