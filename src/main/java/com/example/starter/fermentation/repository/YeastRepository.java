package com.example.starter.fermentation.repository;

import com.example.starter.fermentation.domain.YeastRecoveryRecord;
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
import java.util.List;
import java.util.Optional;

/**
 * 合成酵母单元与回收记录数据访问。所有 SQL 参数化；
 * 同酵母并发投酵由条件更新（status/version 匹配才占用）与唯一约束共同兜底，
 * 按事务提交顺序裁决最新代次与状态。
 */
@Repository
public class YeastRepository {

    private static final RowMapper<YeastUnit> UNIT_MAPPER = (rs, rowNum) -> new YeastUnit(
            rs.getLong("id"),
            rs.getString("unit_code"),
            rs.getInt("generation"),
            YeastStatus.valueOf(rs.getString("status")),
            toLdt(rs.getTimestamp("last_recovered_at")),
            (Long) rs.getObject("occupied_by_batch_id"),
            rs.getInt("version"),
            toLdt(rs.getTimestamp("created_at")));

    private static final RowMapper<YeastRecoveryRecord> RECOVERY_MAPPER = (rs, rowNum) ->
            new YeastRecoveryRecord(
                    rs.getLong("id"),
                    rs.getString("yeast_unit_code"),
                    rs.getLong("batch_id"),
                    rs.getBigDecimal("viability_percent"),
                    rs.getInt("contaminated") != 0,
                    rs.getInt("qualified") != 0,
                    rs.getInt("generation_after"),
                    rs.getInt("unit_version_after"),
                    toLdt(rs.getTimestamp("recovered_at")),
                    toLdt(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public YeastRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /**
     * 登记酵母单元：代次 0、AVAILABLE、版本 1、无占用、无回收时刻。
     *
     * @return 生成 ID
     */
    public long insert(String unitCode, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO yeast_unit (unit_code, generation, status, last_recovered_at,"
                            + " occupied_by_batch_id, version, created_at)"
                            + " VALUES (?, 0, 'AVAILABLE', NULL, NULL, 1, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, unitCode);
            ps.setTimestamp(2, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按编号查酵母单元，不存在返回空。 */
    public Optional<YeastUnit> findByCode(String unitCode) {
        return jdbc.query("SELECT * FROM yeast_unit WHERE unit_code = ?",
                rs -> rs.next() ? Optional.of(UNIT_MAPPER.mapRow(rs, 1)) : Optional.empty(),
                unitCode);
    }

    /**
     * 原子占用：仅当单元仍 AVAILABLE、版本匹配、当前代次不超过 5，且投酵时刻相对最近回收时刻
     * 新鲜（首次单元无回收时刻不限；投酵时刻不得早于回收时刻且不晚于回收后 72 小时，恰 72 小时合法）
     * 时才置 IN_USE 并绑定批次，版本 +1。
     *
     * <p>所有条件在同一行更新（行锁）内按最新已提交数据判定，并发两批投同一单元时先提交方占用成功，
     * 后提交方条件不再匹配，更新 0 行。
     *
     * @return 实际更新行数；0 表示已被占用、版本变化、代次超限或已失效，调用方应重读裁决
     */
    public int tryOccupy(String unitCode, int expectedVersion, long batchId, int maxGeneration,
                         LocalDateTime pitchedAt) {
        return jdbc.update("UPDATE yeast_unit SET status = 'IN_USE', occupied_by_batch_id = ?,"
                        + " version = version + 1"
                        + " WHERE unit_code = ? AND version = ? AND status = 'AVAILABLE'"
                        + " AND generation <= ?"
                        + " AND (last_recovered_at IS NULL"
                        + "      OR (? BETWEEN last_recovered_at AND TIMESTAMPADD(HOUR, 72, last_recovered_at)))",
                batchId, unitCode, expectedVersion, maxGeneration,
                Timestamp.valueOf(pitchedAt));
    }

    /**
     * 回收同事务落库：更新状态/代次/最近回收时刻、解除批次占用，版本 +1。
     * 仅当版本匹配时更新；不合格时 newGeneration 传原代次（不加 1）。
     *
     * @return 实际更新行数；0 表示版本不匹配
     */
    public int applyRecovery(String unitCode, int expectedVersion, YeastStatus newStatus,
                             int newGeneration, LocalDateTime recoveredAt) {
        return jdbc.update("UPDATE yeast_unit SET status = ?, generation = ?,"
                        + " last_recovered_at = ?, occupied_by_batch_id = NULL, version = version + 1"
                        + " WHERE unit_code = ? AND version = ?",
                newStatus.name(), newGeneration, Timestamp.valueOf(recoveredAt),
                unitCode, expectedVersion);
    }

    /** 插入回收记录（uk_recovery_batch 保证每批次仅一条），返回生成 ID。 */
    public long insertRecovery(String unitCode, long batchId, BigDecimal viabilityPercent,
                               boolean contaminated, boolean qualified, int generationAfter,
                               int unitVersionAfter, LocalDateTime recoveredAt,
                               LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO yeast_recovery_record (yeast_unit_code, batch_id, viability_percent,"
                            + " contaminated, qualified, generation_after, unit_version_after,"
                            + " recovered_at, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, unitCode);
            ps.setLong(2, batchId);
            ps.setBigDecimal(3, viabilityPercent);
            ps.setInt(4, contaminated ? 1 : 0);
            ps.setInt(5, qualified ? 1 : 0);
            ps.setInt(6, generationAfter);
            ps.setInt(7, unitVersionAfter);
            ps.setTimestamp(8, Timestamp.valueOf(recoveredAt));
            ps.setTimestamp(9, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按批次查回收记录，不存在返回空。 */
    public Optional<YeastRecoveryRecord> findRecoveryByBatch(long batchId) {
        return jdbc.query("SELECT * FROM yeast_recovery_record WHERE batch_id = ?",
                rs -> rs.next() ? Optional.of(RECOVERY_MAPPER.mapRow(rs, 1)) : Optional.empty(),
                batchId);
    }

    /** 按单元编号查回收记录链（按回收时刻升序，再按 ID 稳定排序）。 */
    public List<YeastRecoveryRecord> findRecoveriesByUnit(String unitCode) {
        return jdbc.query("SELECT * FROM yeast_recovery_record WHERE yeast_unit_code = ?"
                        + " ORDER BY recovered_at ASC, id ASC",
                RECOVERY_MAPPER, unitCode);
    }
}
