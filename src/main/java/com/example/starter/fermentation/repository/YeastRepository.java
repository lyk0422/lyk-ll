package com.example.starter.fermentation.repository;

import com.example.starter.fermentation.domain.YeastRecovery;
import com.example.starter.fermentation.domain.YeastStatus;
import com.example.starter.fermentation.domain.YeastUnit;
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
import java.util.Optional;

/**
 * 合成酵母单元与回收追溯数据访问。所有 SQL 参数化；
 * 一单元同时刻仅供一个主酵批次由投酵条件更新（status/version/占用）原子保证，
 * 批次维度唯一约束 uk_yeast_recovery_batch 保证每批次（含失败批次）仅回收一次。
 */
@Repository
public class YeastRepository {

    private static final RowMapper<YeastUnit> UNIT_MAPPER = (rs, rowNum) -> {
        Long occupiedByBatchId = rs.getLong("occupied_by_batch_id");
        if (rs.wasNull()) {
            occupiedByBatchId = null;
        }
        Timestamp lastRecoveredAt = rs.getTimestamp("last_recovered_at");
        return new YeastUnit(
                rs.getString("unit_code"),
                rs.getInt("generation"),
                YeastStatus.valueOf(rs.getString("status")),
                lastRecoveredAt == null ? null : lastRecoveredAt.toLocalDateTime(),
                occupiedByBatchId,
                rs.getInt("version"));
    };

    private final JdbcTemplate jdbc;

    public YeastRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记酵母单元：初始代次 0、AVAILABLE、无回收时刻、无占用、版本 1。
     *
     * @return 编号重复时抛出 {@link DuplicateKeyException}
     */
    public void insert(String unitCode) {
        jdbc.update("INSERT INTO yeast_unit"
                        + " (unit_code, generation, status, last_recovered_at,"
                        + " occupied_by_batch_id, version)"
                        + " VALUES (?, 0, 'AVAILABLE', NULL, NULL, 1)",
                unitCode);
    }

    public Optional<YeastUnit> findByCode(String unitCode) {
        return jdbc.query("SELECT * FROM yeast_unit WHERE unit_code = ?",
                rs -> rs.next() ? Optional.of(UNIT_MAPPER.mapRow(rs, 1)) : Optional.empty(),
                unitCode);
    }

    /**
     * 按占用批次查询在用酵母单元（status = IN_USE）。回收受理时用于定位批次绑定的单元。
     */
    public Optional<YeastUnit> findOccupiedByBatch(long batchId) {
        return jdbc.query("SELECT * FROM yeast_unit WHERE occupied_by_batch_id = ? AND status = 'IN_USE'",
                rs -> rs.next() ? Optional.of(UNIT_MAPPER.mapRow(rs, 1)) : Optional.empty(),
                batchId);
    }

    /**
     * 原子投酵绑定：仅当单元仍为期望版本且处于 AVAILABLE 时，置 IN_USE 并占用批次、版本加 1。
     * 代次与回收时限由服务层在同一事务内先行判定，条件更新兜底并发互斥。
     *
     * @return 受影响行数；0 表示版本不符或单元已被并发占用
     */
    public int bindForPitch(String unitCode, int expectedVersion, long batchId) {
        return jdbc.update("UPDATE yeast_unit SET status = 'IN_USE', occupied_by_batch_id = ?,"
                        + " version = version + 1"
                        + " WHERE unit_code = ? AND version = ? AND status = 'AVAILABLE'",
                batchId, unitCode, expectedVersion);
    }

    /**
     * 合格回收：转 AVAILABLE、解除占用、记录回收时刻、代次加 1、版本加 1。
     * 期望版本与 IN_USE 状态做并发条件，返回受影响行数。
     */
    public int applyQualifiedRecovery(String unitCode, int expectedVersion,
                                      LocalDateTime recoveredAt) {
        return jdbc.update("UPDATE yeast_unit SET status = 'AVAILABLE', occupied_by_batch_id = NULL,"
                        + " last_recovered_at = ?, generation = generation + 1,"
                        + " version = version + 1"
                        + " WHERE unit_code = ? AND version = ? AND status = 'IN_USE'",
                Timestamp.valueOf(recoveredAt), unitCode, expectedVersion);
    }

    /**
     * 不合格回收：转 SCRAPPED、解除占用、版本加 1，代次与最近回收时刻不变。
     */
    public int applyScrappedRecovery(String unitCode, int expectedVersion) {
        return jdbc.update("UPDATE yeast_unit SET status = 'SCRAPPED', occupied_by_batch_id = NULL,"
                        + " version = version + 1"
                        + " WHERE unit_code = ? AND version = ? AND status = 'IN_USE'",
                unitCode, expectedVersion);
    }

    /**
     * 写入回收追溯记录。
     *
     * @return 生成的回收记录 ID
     */
    public long insertRecovery(String unitCode, long batchId, LocalDateTime recoveredAt,
                               BigDecimal viability, boolean contaminated, boolean qualified,
                               int generationAfter, YeastStatus statusAfter,
                               LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO yeast_recovery (unit_code, batch_id, recovered_at,"
                            + " viability_percent, contaminated, qualified, generation_after,"
                            + " status_after, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, unitCode);
            ps.setLong(2, batchId);
            ps.setTimestamp(3, Timestamp.valueOf(recoveredAt));
            ps.setBigDecimal(4, viability);
            ps.setInt(5, contaminated ? 1 : 0);
            ps.setInt(6, qualified ? 1 : 0);
            ps.setInt(7, generationAfter);
            ps.setString(8, statusAfter.name());
            ps.setTimestamp(9, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public boolean recoveryExistsForBatch(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_recovery WHERE batch_id = ?", Integer.class, batchId);
        return count != null && count > 0;
    }

    public Optional<YeastRecovery> findRecoveryByBatch(long batchId) {
        return jdbc.query("SELECT * FROM yeast_recovery WHERE batch_id = ?", rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new YeastRecovery(
                    rs.getLong("id"),
                    rs.getString("unit_code"),
                    rs.getLong("batch_id"),
                    rs.getTimestamp("recovered_at").toLocalDateTime(),
                    rs.getBigDecimal("viability_percent"),
                    rs.getInt("contaminated") != 0,
                    rs.getInt("qualified") != 0,
                    rs.getInt("generation_after"),
                    YeastStatus.valueOf(rs.getString("status_after"))));
        }, batchId);
    }
}
