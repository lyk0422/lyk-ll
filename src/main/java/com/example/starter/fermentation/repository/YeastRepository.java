package com.example.starter.fermentation.repository;

import com.example.starter.fermentation.domain.YeastRecovery;
import com.example.starter.fermentation.domain.YeastUnit;
import com.example.starter.fermentation.domain.YeastUnitStatus;
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
 * 酵母单元与回收记录数据访问。所有 SQL 参数化；
 * 并发投酵/回收由乐观锁（version 条件更新）与唯一约束兜底。
 */
@Repository
public class YeastRepository {

    private static final RowMapper<YeastUnit> UNIT_MAPPER = (rs, rowNum) -> new YeastUnit(
            rs.getLong("id"),
            rs.getString("unit_code"),
            rs.getInt("generation"),
            YeastUnitStatus.valueOf(rs.getString("status")),
            toLdt(rs.getTimestamp("last_recovered_at")),
            rs.getInt("version"),
            rs.getObject("active_batch_id") == null ? null : rs.getLong("active_batch_id"));

    private static final RowMapper<YeastRecovery> RECOVERY_MAPPER = (rs, rowNum) -> new YeastRecovery(
            rs.getLong("id"),
            rs.getLong("yeast_unit_id"),
            rs.getLong("batch_id"),
            toLdt(rs.getTimestamp("recovered_at")),
            rs.getBigDecimal("viability_percent"),
            rs.getInt("contaminated") != 0,
            rs.getString("outcome"),
            rs.getInt("generation_after"));

    private final JdbcTemplate jdbc;

    public YeastRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /**
     * 登记酵母单元：初始代次 0、可用、版本 1。编号唯一冲突由 uk_yeast_unit_code 抛出。
     *
     * @return 生成的酵母单元 ID
     */
    public long insert(String unitCode) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO yeast_unit (unit_code, generation, status, last_recovered_at, version,"
                            + " active_batch_id) VALUES (?, 0, ?, NULL, 1, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, unitCode);
            ps.setString(2, YeastUnitStatus.AVAILABLE.name());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<YeastUnit> findByCode(String unitCode) {
        return jdbc.query("SELECT * FROM yeast_unit WHERE unit_code = ?",
                rs -> rs.next() ? Optional.of(UNIT_MAPPER.mapRow(rs, 1)) : Optional.empty(),
                unitCode);
    }

    public Optional<YeastUnit> findById(long id) {
        return jdbc.query("SELECT * FROM yeast_unit WHERE id = ?",
                rs -> rs.next() ? Optional.of(UNIT_MAPPER.mapRow(rs, 1)) : Optional.empty(),
                id);
    }

    /**
     * 投酵绑定：AVAILABLE → IN_USE 并占用批次，版本 +1。
     * 仅当当前版本与期望版本一致且状态仍为 AVAILABLE 时生效（乐观锁）。
     *
     * @return 是否更新成功；false 表示并发下版本或状态已变化
     */
    public boolean bindToBatch(long unitId, int expectedVersion, long batchId) {
        return jdbc.update("UPDATE yeast_unit SET status = ?, active_batch_id = ?,"
                        + " version = version + 1 WHERE id = ? AND version = ? AND status = ?",
                YeastUnitStatus.IN_USE.name(), batchId, unitId, expectedVersion,
                YeastUnitStatus.AVAILABLE.name()) == 1;
    }

    /**
     * 投酵前惰性失效：AVAILABLE → EXPIRED，版本 +1（距最近回收超过 72 小时）。
     * 仅当当前版本一致时生效。
     *
     * @return 是否更新成功
     */
    public boolean markExpired(long unitId, int expectedVersion) {
        return jdbc.update("UPDATE yeast_unit SET status = ?, version = version + 1"
                        + " WHERE id = ? AND version = ? AND status = ?",
                YeastUnitStatus.EXPIRED.name(), unitId, expectedVersion,
                YeastUnitStatus.AVAILABLE.name()) == 1;
    }

    /**
     * 回收：IN_USE → 目标状态，解除批次占用，记录最近回收时刻，合格时代次 +1，版本 +1。
     * 仅当当前版本一致且状态仍为 IN_USE 时生效（保证已回收单元不可再增代次）。
     *
     * @param recycled 是否合格回收（合格转可用且代次 +1，否则报废）
     * @return 是否更新成功；false 表示并发下版本或状态已变化
     */
    public boolean applyRecovery(long unitId, int expectedVersion, boolean recycled,
                                 LocalDateTime recoveredAt) {
        String targetStatus = recycled
                ? YeastUnitStatus.AVAILABLE.name() : YeastUnitStatus.RETIRED.name();
        String generationExpr = recycled ? "generation + 1" : "generation";
        return jdbc.update("UPDATE yeast_unit SET status = ?, generation = " + generationExpr
                        + ", last_recovered_at = ?, active_batch_id = NULL, version = version + 1"
                        + " WHERE id = ? AND version = ? AND status = ?",
                targetStatus, Timestamp.valueOf(recoveredAt), unitId, expectedVersion,
                YeastUnitStatus.IN_USE.name()) == 1;
    }

    /**
     * 写入回收记录。batch_id 唯一约束保证失败批次也只能回收一次。
     *
     * @return 生成的回收记录 ID
     */
    public long insertRecovery(long yeastUnitId, long batchId, LocalDateTime recoveredAt,
                               BigDecimal viabilityPercent, boolean contaminated,
                               String outcome, int generationAfter) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO yeast_recovery (yeast_unit_id, batch_id, recovered_at,"
                            + " viability_percent, contaminated, outcome, generation_after)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, yeastUnitId);
            ps.setLong(2, batchId);
            ps.setTimestamp(3, Timestamp.valueOf(recoveredAt));
            ps.setBigDecimal(4, viabilityPercent);
            ps.setInt(5, contaminated ? 1 : 0);
            ps.setString(6, outcome);
            ps.setInt(7, generationAfter);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询酵母单元的回收追溯链（按回收时刻升序）。
     */
    public List<YeastRecovery> findRecoveries(long yeastUnitId) {
        return jdbc.query("SELECT * FROM yeast_recovery WHERE yeast_unit_id = ?"
                + " ORDER BY recovered_at, id", RECOVERY_MAPPER, yeastUnitId);
    }
}
