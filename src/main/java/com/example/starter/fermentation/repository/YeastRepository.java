package com.example.starter.fermentation.repository;

import com.example.starter.fermentation.domain.RecycleResult;
import com.example.starter.fermentation.domain.YeastRecycleRecord;
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
 * 酵母单元与回收追溯数据访问。所有 SQL 参数化；
 * 并发投酵由 uk_yeast_active_batch 唯一约束与条件更新（状态 + 版本）兜底，
 * 每个绑定批次仅回收一次由 uk_recycle_batch 唯一约束兜底。
 */
@Repository
public class YeastRepository {

    private static final RowMapper<YeastUnit> UNIT_MAPPER = (rs, rowNum) -> new YeastUnit(
            rs.getLong("id"),
            rs.getString("yeast_code"),
            rs.getInt("generation"),
            YeastUnitStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            toLdt(rs.getTimestamp("last_recovered_at")),
            rs.getObject("active_batch_id", Long.class));

    private static final RowMapper<YeastRecycleRecord> RECYCLE_MAPPER = (rs, rowNum) ->
            new YeastRecycleRecord(
                    rs.getLong("id"),
                    rs.getLong("yeast_unit_id"),
                    rs.getLong("batch_id"),
                    rs.getInt("generation_before"),
                    rs.getInt("generation_after"),
                    rs.getBigDecimal("viability_percent"),
                    rs.getInt("contaminated") != 0,
                    RecycleResult.valueOf(rs.getString("result")),
                    toLdt(rs.getTimestamp("recovered_at")));

    private final JdbcTemplate jdbc;

    public YeastRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /**
     * 登记酵母单元：初始代次 0、可用、版本 1，无回收时刻。
     *
     * @return 生成的酵母单元 ID
     */
    public long insert(String yeastCode) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO yeast_unit (yeast_code, generation, status, version)"
                            + " VALUES (?, 0, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, yeastCode);
            ps.setString(2, YeastUnitStatus.AVAILABLE.name());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<YeastUnit> findByCode(String yeastCode) {
        return jdbc.query("SELECT * FROM yeast_unit WHERE yeast_code = ?",
                rs -> rs.next() ? Optional.of(UNIT_MAPPER.mapRow(rs, 1)) : Optional.empty(),
                yeastCode);
    }

    public Optional<YeastUnit> findById(long id) {
        return jdbc.query("SELECT * FROM yeast_unit WHERE id = ?",
                rs -> rs.next() ? Optional.of(UNIT_MAPPER.mapRow(rs, 1)) : Optional.empty(), id);
    }

    /**
     * 投酵绑定：仅当单元当前为可用且版本匹配时置为 IN_USE 并绑定批次，版本加 1。
     * 条件更新与行锁保证并发下仅一个事务生效。
     *
     * @return 更新行数，0 表示状态或版本已变化
     */
    public int bindToBatch(long id, long batchId, int expectedVersion) {
        return jdbc.update("UPDATE yeast_unit SET status = ?, active_batch_id = ?,"
                        + " version = version + 1"
                        + " WHERE id = ? AND status = ? AND version = ?",
                YeastUnitStatus.IN_USE.name(), batchId, id,
                YeastUnitStatus.AVAILABLE.name(), expectedVersion);
    }

    /**
     * 回收：仅当单元当前为投酵绑定中、绑定该批次且版本匹配时生效；
     * 解除批次占用、记录回收时刻、按结果更新代次与状态，版本加 1。
     *
     * @return 更新行数，0 表示状态、绑定或版本已变化
     */
    public int applyRecycle(long id, long batchId, YeastUnitStatus newStatus, int newGeneration,
                            LocalDateTime recoveredAt, int expectedVersion) {
        return jdbc.update("UPDATE yeast_unit SET status = ?, generation = ?,"
                        + " last_recovered_at = ?, active_batch_id = NULL, version = version + 1"
                        + " WHERE id = ? AND status = ? AND active_batch_id = ? AND version = ?",
                newStatus.name(), newGeneration, Timestamp.valueOf(recoveredAt), id,
                YeastUnitStatus.IN_USE.name(), batchId, expectedVersion);
    }

    /**
     * 记录回收追溯（每个绑定批次仅一条，由 uk_recycle_batch 兜底）。
     *
     * @return 生成的记录 ID
     */
    public long insertRecycleRecord(long yeastUnitId, long batchId, int generationBefore,
                                    int generationAfter, BigDecimal viabilityPercent,
                                    boolean contaminated, RecycleResult result,
                                    LocalDateTime recoveredAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO yeast_recycle_record (yeast_unit_id, batch_id, generation_before,"
                            + " generation_after, viability_percent, contaminated, result, recovered_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, yeastUnitId);
            ps.setLong(2, batchId);
            ps.setInt(3, generationBefore);
            ps.setInt(4, generationAfter);
            ps.setBigDecimal(5, viabilityPercent);
            ps.setInt(6, contaminated ? 1 : 0);
            ps.setString(7, result.name());
            ps.setTimestamp(8, Timestamp.valueOf(recoveredAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询酵母单元的回收追溯链（按回收时刻升序，可复算代次演进）。
     */
    public List<YeastRecycleRecord> findRecycles(long yeastUnitId) {
        return jdbc.query("SELECT * FROM yeast_recycle_record WHERE yeast_unit_id = ?"
                        + " ORDER BY recovered_at, id",
                RECYCLE_MAPPER, yeastUnitId);
    }
}
