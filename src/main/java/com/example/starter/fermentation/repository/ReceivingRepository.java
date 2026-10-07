package com.example.starter.fermentation.repository;

import com.example.starter.fermentation.domain.MergeReceipt;
import com.example.starter.fermentation.domain.MergeReceiptSource;
import com.example.starter.fermentation.domain.ReceivingTank;
import com.example.starter.fermentation.domain.SourceVolume;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 合批接收台账数据访问：接收罐、源批实际升数、接收单与来源明细。
 * 所有 SQL 参数化；接收罐编号与源批唯一登记由唯一约束兜底；
 * 接收罐容量校验通过行级锁（SELECT ... FOR UPDATE）按事务次序串行化。
 */
@Repository
public class ReceivingRepository {

    private static final RowMapper<ReceivingTank> TANK_MAPPER = (rs, rowNum) -> new ReceivingTank(
            rs.getLong("id"),
            rs.getString("tank_code"),
            rs.getBigDecimal("capacity_liters"),
            rs.getBigDecimal("received_liters"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<SourceVolume> VOLUME_MAPPER = (rs, rowNum) -> new SourceVolume(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getBigDecimal("volume_liters"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<MergeReceipt> RECEIPT_MAPPER = (rs, rowNum) -> new MergeReceipt(
            rs.getLong("id"),
            rs.getLong("receiving_tank_id"),
            rs.getString("curve_code"),
            rs.getInt("curve_version"),
            rs.getBigDecimal("total_liters"),
            rs.getTimestamp("received_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<MergeReceiptSource> SOURCE_MAPPER = (rs, rowNum) -> new MergeReceiptSource(
            rs.getLong("id"),
            rs.getLong("receipt_id"),
            rs.getLong("batch_id"),
            rs.getBigDecimal("volume_liters"));

    private final JdbcTemplate jdbc;

    public ReceivingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记接收罐：容量正数，版本从 1 开始，已接收量 0。
     *
     * @return 生成的接收罐 ID
     */
    public long insertTank(String tankCode, BigDecimal capacityLiters, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO receiving_tank (tank_code, capacity_liters, received_liters, version, created_at)"
                            + " VALUES (?, ?, 0, 1, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, tankCode);
            ps.setBigDecimal(2, capacityLiters);
            ps.setTimestamp(3, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ReceivingTank> findTankByCode(String tankCode) {
        return jdbc.query("SELECT * FROM receiving_tank WHERE tank_code = ?",
                rs -> rs.next() ? Optional.of(TANK_MAPPER.mapRow(rs, 1)) : Optional.empty(), tankCode);
    }

    public Optional<ReceivingTank> findTankById(long id) {
        return jdbc.query("SELECT * FROM receiving_tank WHERE id = ?",
                rs -> rs.next() ? Optional.of(TANK_MAPPER.mapRow(rs, 1)) : Optional.empty(), id);
    }

    /**
     * 按编号锁定接收罐行（FOR UPDATE）：容量与已接收量的校验-更新按事务次序串行。
     */
    public Optional<ReceivingTank> lockTankByCode(String tankCode) {
        return jdbc.query("SELECT * FROM receiving_tank WHERE tank_code = ? FOR UPDATE",
                rs -> rs.next() ? Optional.of(TANK_MAPPER.mapRow(rs, 1)) : Optional.empty(), tankCode);
    }

    /**
     * 条件调容：仅当当前版本等于 expectedVersion 时生效，版本加 1。
     *
     * @return 受影响行数；0 表示版本已变更（旧版 409）
     */
    public int tryUpdateCapacity(String tankCode, BigDecimal capacityLiters, int expectedVersion) {
        return jdbc.update("UPDATE receiving_tank SET capacity_liters = ?, version = version + 1"
                        + " WHERE tank_code = ? AND version = ?",
                capacityLiters, tankCode, expectedVersion);
    }

    /**
     * 接收落账：增加已接收量并版本加 1（与源核销、接收单插入同事务）。
     */
    public void addReceived(long tankId, BigDecimal deltaLiters) {
        jdbc.update("UPDATE receiving_tank SET received_liters = received_liters + ?,"
                + " version = version + 1 WHERE id = ?", deltaLiters, tankId);
    }

    /**
     * 登记源批实际清酒升数：每批仅一次（uk_source_volume_batch 兜底），版本 1，登记后冻结。
     *
     * @return 生成的台账记录 ID
     */
    public long insertSourceVolume(long batchId, BigDecimal volumeLiters, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO source_volume (batch_id, volume_liters, version, created_at)"
                            + " VALUES (?, ?, 1, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setBigDecimal(2, volumeLiters);
            ps.setTimestamp(3, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<SourceVolume> findVolumeByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM source_volume WHERE batch_id = ?",
                rs -> rs.next() ? Optional.of(VOLUME_MAPPER.mapRow(rs, 1)) : Optional.empty(), batchId);
    }

    /**
     * 查询发酵罐容量（升），用于校验源批登记量不超过其发酵罐容量。
     */
    public Optional<BigDecimal> findFermentationTankCapacity(long tankId) {
        return jdbc.query("SELECT capacity_liters FROM fermentation_tank WHERE id = ?",
                rs -> rs.next() ? Optional.of(rs.getBigDecimal(1)) : Optional.empty(), tankId);
    }

    /**
     * 插入合批接收单。
     *
     * @return 生成的接收单 ID
     */
    public long insertReceipt(long receivingTankId, String curveCode, int curveVersion,
                              BigDecimal totalLiters, LocalDateTime receivedAt,
                              LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO merge_receipt (receiving_tank_id, curve_code, curve_version,"
                            + " total_liters, received_at, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, receivingTankId);
            ps.setString(2, curveCode);
            ps.setInt(3, curveVersion);
            ps.setBigDecimal(4, totalLiters);
            ps.setTimestamp(5, Timestamp.valueOf(receivedAt));
            ps.setTimestamp(6, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 插入接收来源明细（完整登记量）。
     */
    public void insertReceiptSource(long receiptId, long batchId, BigDecimal volumeLiters) {
        jdbc.update("INSERT INTO merge_receipt_source (receipt_id, batch_id, volume_liters)"
                + " VALUES (?, ?, ?)", receiptId, batchId, volumeLiters);
    }

    /**
     * 查询接收罐的全部接收单（按 ID 升序，可复算已接收总量）。
     */
    public List<MergeReceipt> findReceiptsByTankId(long receivingTankId) {
        return jdbc.query("SELECT * FROM merge_receipt WHERE receiving_tank_id = ? ORDER BY id",
                RECEIPT_MAPPER, receivingTankId);
    }

    /**
     * 查询接收单的来源明细（按批次 ID 升序）。
     */
    public List<MergeReceiptSource> findReceiptSources(long receiptId) {
        return jdbc.query("SELECT * FROM merge_receipt_source WHERE receipt_id = ? ORDER BY batch_id",
                SOURCE_MAPPER, receiptId);
    }
}
