package com.example.starter.deicing.db;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 除冰液配比、批次与消耗台账数据访问。
 * 库存写操作均先锁定 deicing_fluid 行（SELECT ... FOR UPDATE）串行化。
 */
@Repository
public class FluidRepository {

    private static final RowMapper<DeicingFluidPo> FLUID_MAPPER = (ResultSet rs, int rowNum) -> {
        DeicingFluidPo po = new DeicingFluidPo();
        po.setFluidCode(rs.getString("fluid_code"));
        po.setFluidName(rs.getString("fluid_name"));
        po.setConsumptionPerMinuteLiters(rs.getBigDecimal("consumption_per_minute_liters"));
        po.setOpeningLiters(rs.getBigDecimal("opening_liters"));
        po.setCreatedAt(RowMappers.instant(rs, "created_at"));
        return po;
    };

    private static final RowMapper<FluidBatchPo> BATCH_MAPPER = (ResultSet rs, int rowNum) -> mapBatch(rs);

    private static FluidBatchPo mapBatch(ResultSet rs) throws SQLException {
        FluidBatchPo po = new FluidBatchPo();
        po.setId(rs.getLong("id"));
        po.setBatchNo(rs.getString("batch_no"));
        po.setFluidCode(rs.getString("fluid_code"));
        po.setSource(rs.getString("source"));
        po.setQuantityLiters(rs.getBigDecimal("quantity_liters"));
        po.setRemainingLiters(rs.getBigDecimal("remaining_liters"));
        po.setExpiryTime(RowMappers.instant(rs, "expiry_time"));
        po.setCreatedAt(RowMappers.instant(rs, "created_at"));
        return po;
    }

    private final JdbcTemplate jdbcTemplate;

    public FluidRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DeicingFluidPo findFluid(String fluidCode) {
        List<DeicingFluidPo> list = jdbcTemplate.query(
                "SELECT fluid_code, fluid_name, consumption_per_minute_liters, opening_liters, created_at"
                        + " FROM deicing_fluid WHERE fluid_code = ?",
                FLUID_MAPPER, fluidCode);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<DeicingFluidPo> findAllFluids() {
        return jdbcTemplate.query(
                "SELECT fluid_code, fluid_name, consumption_per_minute_liters, opening_liters, created_at"
                        + " FROM deicing_fluid ORDER BY fluid_code",
                FLUID_MAPPER);
    }

    /**
     * 锁定除冰液行，串行化该液的预占/结算/借用/入库。必须在事务内调用。
     */
    public DeicingFluidPo lockFluid(String fluidCode) {
        List<DeicingFluidPo> list = jdbcTemplate.query(
                "SELECT fluid_code, fluid_name, consumption_per_minute_liters, opening_liters, created_at"
                        + " FROM deicing_fluid WHERE fluid_code = ? FOR UPDATE",
                FLUID_MAPPER, fluidCode);
        return list.isEmpty() ? null : list.get(0);
    }

    // ---- 批次 --------------------------------------------------------------

    public void insertBatch(FluidBatchPo po) {
        jdbcTemplate.update("""
                        INSERT INTO fluid_batch
                            (batch_no, fluid_code, source, quantity_liters, remaining_liters,
                             expiry_time, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                po.getBatchNo(), po.getFluidCode(), po.getSource(), po.getQuantityLiters(),
                po.getRemainingLiters(), Timestamp.from(po.getExpiryTime()),
                Timestamp.from(po.getCreatedAt()));
    }

    /**
     * 按先到期先出（FEFO）顺序锁定可扣减批次：未过期且剩余量大于零。
     */
    public List<FluidBatchPo> lockUsableBatchesFefo(String fluidCode, Instant now) {
        return jdbcTemplate.query("""
                        SELECT id, batch_no, fluid_code, source, quantity_liters, remaining_liters,
                               expiry_time, created_at
                        FROM fluid_batch
                        WHERE fluid_code = ? AND remaining_liters > 0 AND expiry_time > ?
                        ORDER BY expiry_time, id
                        FOR UPDATE
                        """,
                BATCH_MAPPER, fluidCode, Timestamp.from(now));
    }

    /**
     * 该液效期最晚的批次（不论是否过期），用于结算超扣与归还回补的落点。
     */
    public FluidBatchPo lockLatestExpiryBatch(String fluidCode) {
        List<FluidBatchPo> list = jdbcTemplate.query("""
                        SELECT id, batch_no, fluid_code, source, quantity_liters, remaining_liters,
                               expiry_time, created_at
                        FROM fluid_batch
                        WHERE fluid_code = ?
                        ORDER BY expiry_time DESC, id DESC
                        LIMIT 1
                        FOR UPDATE
                        """,
                BATCH_MAPPER, fluidCode);
        return list.isEmpty() ? null : list.get(0);
    }

    public void updateBatchRemaining(long batchId, BigDecimal remainingLiters) {
        jdbcTemplate.update("UPDATE fluid_batch SET remaining_liters = ? WHERE id = ?",
                remainingLiters, batchId);
    }

    /**
     * 未过期批次剩余量合计（升）：可用于预占/借出的物理库存。
     */
    public BigDecimal sumUsableRemaining(String fluidCode, Instant now) {
        return sum("""
                SELECT COALESCE(SUM(remaining_liters), 0) FROM fluid_batch
                WHERE fluid_code = ? AND expiry_time > ?
                """, fluidCode, Timestamp.from(now));
    }

    /**
     * 全部批次剩余量合计（升），含已过期批次。
     */
    public BigDecimal sumTotalRemaining(String fluidCode) {
        return sum("SELECT COALESCE(SUM(remaining_liters), 0) FROM fluid_batch WHERE fluid_code = ?",
                fluidCode);
    }

    /**
     * 补料入库量合计（升），不含期初批次。
     */
    public BigDecimal sumInbound(String fluidCode) {
        return sum("""
                SELECT COALESCE(SUM(quantity_liters), 0) FROM fluid_batch
                WHERE fluid_code = ? AND source = 'INBOUND'
                """, fluidCode);
    }

    // ---- 消耗台账 ----------------------------------------------------------

    public void insertConsumption(String requestId, String fluidCode, BigDecimal quantityLiters,
                                  int jobMinutes, Instant createdAt) {
        jdbcTemplate.update("""
                        INSERT INTO fluid_consumption
                            (request_id, fluid_code, quantity_liters, job_minutes, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                requestId, fluidCode, quantityLiters, jobMinutes, Timestamp.from(createdAt));
    }

    public BigDecimal sumConsumed(String fluidCode) {
        return sum("""
                SELECT COALESCE(SUM(quantity_liters), 0) FROM fluid_consumption
                WHERE fluid_code = ?
                """, fluidCode);
    }

    private BigDecimal sum(String sql, Object... args) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }
}
