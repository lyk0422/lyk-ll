package com.example.starter.fermentation.repository;

import com.example.starter.fermentation.domain.MergeReceipt;
import com.example.starter.fermentation.domain.MergeReceiptItem;
import com.example.starter.fermentation.domain.ReceivingTank;
import com.example.starter.fermentation.domain.SourceLiquor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 合批接收台账数据访问：接收罐、源批清酒量、唯一核销与受理单。
 * 容量与升数 DECIMAL(10,3)，所有 SQL 参数化；并发由乐观版本与唯一约束兜底。
 */
@Repository
public class ReceivingRepository {

    private static final RowMapper<ReceivingTank> TANK_MAPPER = (rs, n) -> new ReceivingTank(
            rs.getLong("id"),
            rs.getString("tank_code"),
            rs.getBigDecimal("capacity_liters"),
            rs.getBigDecimal("received_liters"),
            rs.getInt("version"),
            toLdt(rs.getTimestamp("created_at")),
            toLdt(rs.getTimestamp("updated_at")));

    private static final RowMapper<SourceLiquor> SOURCE_MAPPER = (rs, n) -> new SourceLiquor(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getBigDecimal("liters"),
            rs.getInt("frozen") != 0,
            rs.getInt("version"),
            toLdt(rs.getTimestamp("created_at")));

    private static final RowMapper<MergeReceiptItem> ITEM_MAPPER = (rs, n) -> new MergeReceiptItem(
            rs.getLong("id"),
            rs.getLong("receipt_id"),
            rs.getLong("batch_id"),
            rs.getString("batch_no"),
            rs.getInt("curve_version"),
            rs.getBigDecimal("liters"));

    private static final RowMapper<MergeReceipt> RECEIPT_MAPPER = (rs, n) -> new MergeReceipt(
            rs.getLong("id"),
            rs.getLong("receiving_tank_id"),
            rs.getBigDecimal("total_liters"),
            toLdt(rs.getTimestamp("received_at")),
            toLdt(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public ReceivingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /** 登记合成接收罐（容量升数），版本初始 1，已接收量初始 0。 */
    public long insertTank(String tankCode, BigDecimal capacityLiters, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO receiving_tank (tank_code, capacity_liters, received_liters,"
                            + " version, created_at, updated_at) VALUES (?, ?, 0, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, tankCode);
            ps.setBigDecimal(2, capacityLiters);
            ps.setTimestamp(3, Timestamp.valueOf(now));
            ps.setTimestamp(4, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ReceivingTank> findTankById(long id) {
        return jdbc.query("SELECT * FROM receiving_tank WHERE id = ?",
                rs -> rs.next() ? Optional.of(TANK_MAPPER.mapRow(rs, 1)) : Optional.empty(), id);
    }

    public Optional<ReceivingTank> findTankByCode(String tankCode) {
        return jdbc.query("SELECT * FROM receiving_tank WHERE tank_code = ?",
                rs -> rs.next() ? Optional.of(TANK_MAPPER.mapRow(rs, 1)) : Optional.empty(), tankCode);
    }

    /**
     * 容量不得缩到已接收量以下的条件更新：版本匹配且新容量不小于当前已接收量时生效，
     * 成功版本加 1。
     *
     * @return 受影响行数；0 表示版本失配或新容量小于当前已接收量
     */
    public int updateCapacityIfNotBelowReceived(long id, BigDecimal capacityLiters,
                                                int expectedVersion, LocalDateTime now) {
        return jdbc.update("UPDATE receiving_tank SET capacity_liters = ?, version = version + 1,"
                        + " updated_at = ? WHERE id = ? AND version = ? AND capacity_liters >= received_liters",
                capacityLiters, Timestamp.valueOf(now), id, expectedVersion);
    }

    /**
     * 合批受理的原子入账：版本匹配且 已接收量 + 本次合计 &lt;= 容量（恰满允许）时，
     * 累加接收量并版本加 1。数据库 CHECK 约束同时兜底超容量。
     *
     * @return 受影响行数；0 表示版本失配或余量不足
     */
    public int applyReceive(long tankId, BigDecimal addLiters, int expectedVersion,
                            LocalDateTime now) {
        return jdbc.update("UPDATE receiving_tank SET received_liters = received_liters + ?,"
                        + " version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND version = ? AND received_liters + ? <= capacity_liters",
                addLiters, Timestamp.valueOf(now), tankId, expectedVersion, addLiters);
    }

    /** 登记源批实际清酒量（登记即冻结，frozen=1）。 */
    public long insertSourceLiquor(long batchId, BigDecimal liters, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO source_liquor_register (batch_id, liters, frozen, version, created_at)"
                            + " VALUES (?, ?, 1, 1, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setBigDecimal(2, liters);
            ps.setTimestamp(3, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<SourceLiquor> findSourceLiquorByBatch(long batchId) {
        return jdbc.query("SELECT * FROM source_liquor_register WHERE batch_id = ?",
                rs -> rs.next() ? Optional.of(SOURCE_MAPPER.mapRow(rs, 1)) : Optional.empty(),
                batchId);
    }

    /** 查询源批所在发酵罐容量（升），批次不存在返回空。 */
    public Optional<Integer> findFermentationTankCapacityByBatch(long batchId) {
        return jdbc.query("SELECT t.capacity_liters FROM fermentation_batch b"
                        + " JOIN fermentation_tank t ON b.tank_id = t.id WHERE b.id = ?",
                rs -> rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty(), batchId);
    }

    /**
     * 写入唯一核销（SINGLE 单笔转出 / MERGE 合批）。
     * 并发重复消费由 uk_writeoff_batch 唯一约束拒绝（DuplicateKeyException）。
     */
    public void insertWriteoff(long batchId, String channel, Long receiptId, LocalDateTime now) {
        jdbc.update("INSERT INTO liquor_writeoff (batch_id, channel, receipt_id, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                batchId, channel, receiptId, Timestamp.valueOf(now));
    }

    public boolean writeoffExists(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM liquor_writeoff WHERE batch_id = ?", Integer.class, batchId);
        return count != null && count > 0;
    }

    /** 创建合批受理单，返回受理单 ID。 */
    public long insertReceipt(long tankId, BigDecimal totalLiters, LocalDateTime receivedAt,
                              LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO merge_receipt (receiving_tank_id, total_liters, received_at, created_at)"
                            + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tankId);
            ps.setBigDecimal(2, totalLiters);
            ps.setTimestamp(3, Timestamp.valueOf(receivedAt));
            ps.setTimestamp(4, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertReceiptItem(long receiptId, long batchId, String batchNo, int curveVersion,
                                  BigDecimal liters) {
        jdbc.update("INSERT INTO merge_receipt_item (receipt_id, batch_id, batch_no,"
                        + " curve_version, liters) VALUES (?, ?, ?, ?, ?)",
                receiptId, batchId, batchNo, curveVersion, liters);
    }

    public Optional<MergeReceipt> findReceiptById(long receiptId) {
        return jdbc.query("SELECT * FROM merge_receipt WHERE id = ?",
                rs -> rs.next() ? Optional.of(RECEIPT_MAPPER.mapRow(rs, 1)) : Optional.empty(),
                receiptId);
    }

    public List<MergeReceiptItem> findItemsByReceipt(long receiptId) {
        return jdbc.query("SELECT * FROM merge_receipt_item WHERE receipt_id = ? ORDER BY id",
                ITEM_MAPPER, receiptId);
    }

    public List<MergeReceipt> findReceiptsByTank(long tankId) {
        return jdbc.query("SELECT * FROM merge_receipt WHERE receiving_tank_id = ? ORDER BY id",
                RECEIPT_MAPPER, tankId);
    }
}
