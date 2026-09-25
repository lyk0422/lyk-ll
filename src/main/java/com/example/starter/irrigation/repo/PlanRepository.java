package com.example.starter.irrigation.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * 配水计划版本与明细仓储。版本一经写入明细不可变；生效唯一性由
 * {@code effective_unique_key} 唯一索引在数据库层兜底，并发发布仅一个成功。
 */
@Repository
public class PlanRepository {

    /** 计划版本行。 */
    public record PlanVersionRow(long id, String groupCode, long turnNo, int versionNo, String status,
                                 long availableCubicMeter, String requestId, String fingerprint,
                                 Instant publishedAt) {
    }

    /** 计划明细行，权重四位小数，水量单位立方米。 */
    public record PlanLineRow(String outletCode, long areaSquareMeter,
                              BigDecimal weight, long planCubicMeter, long carryoverCubicMeter) {
    }

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按幂等键查首次发布的版本（后续可能已被新版本取代，仍按其当前状态返回）。 */
    public Optional<PlanVersionRow> findByRequestId(String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM irr_plan_version WHERE request_id = ?", VERSION_MAPPER, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查同轮同组当前生效版本。 */
    public Optional<PlanVersionRow> findEffective(long turnNo, String groupCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT * FROM irr_plan_version
                    WHERE turn_no = ? AND group_code = ? AND status = 'EFFECTIVE'
                    """, VERSION_MAPPER, turnNo, groupCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查指定版本号的计划版本（旧版本可查）。 */
    public Optional<PlanVersionRow> findVersion(long turnNo, String groupCode, int versionNo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT * FROM irr_plan_version
                    WHERE turn_no = ? AND group_code = ? AND version_no = ?
                    """, VERSION_MAPPER, turnNo, groupCode, versionNo));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 列同轮同组全部版本，按版本号升序。 */
    public List<PlanVersionRow> listVersions(long turnNo, String groupCode) {
        return jdbc.query("""
                SELECT * FROM irr_plan_version
                WHERE turn_no = ? AND group_code = ?
                ORDER BY version_no
                """, VERSION_MAPPER, turnNo, groupCode);
    }

    /** 版本明细，按斗口编号升序。 */
    public List<PlanLineRow> findLines(long planId) {
        return jdbc.query("""
                SELECT outlet_code, area_square_meter, weight, plan_cubic_meter, carryover_cubic_meter
                FROM irr_plan_line WHERE plan_id = ? ORDER BY outlet_code
                """, LINE_MAPPER, planId);
    }

    /**
     * 计算同轮同组下一个版本号（调用方须在事务内）。
     */
    public int nextVersionNo(long turnNo, String groupCode) {
        Integer max = jdbc.queryForObject("""
                SELECT MAX(version_no) FROM irr_plan_version
                WHERE turn_no = ? AND group_code = ?
                """, Integer.class, turnNo, groupCode);
        return (max == null ? 0 : max) + 1;
    }

    /**
     * 将同轮同组当前生效版本标记为已取代并释放生效唯一键（调用方须在事务内）。
     */
    public void supersedeEffective(long turnNo, String groupCode) {
        jdbc.update("""
                UPDATE irr_plan_version
                SET status = 'SUPERSEDED', effective_unique_key = NULL
                WHERE turn_no = ? AND group_code = ? AND status = 'EFFECTIVE'
                """, turnNo, groupCode);
    }

    /**
     * 插入新生效版本；{@code effective_unique_key} 唯一索引保证并发发布仅一个成功。
     *
     * @return 新计划版本 ID
     */
    public long insertEffectiveVersion(PlanVersionRow row) {
        String effectiveKey = row.turnNo() + "|" + row.groupCode();
        return insert(row, effectiveKey);
    }

    /** 插入计划明细（调用方须在事务内）。 */
    public void insertLines(long planId, List<PlanLineRow> lines) {
        for (PlanLineRow line : lines) {
            jdbc.update("""
                    INSERT INTO irr_plan_line
                        (plan_id, outlet_code, area_square_meter, weight,
                         plan_cubic_meter, carryover_cubic_meter)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, planId, line.outletCode(), line.areaSquareMeter(),
                    line.weight(), line.planCubicMeter(), line.carryoverCubicMeter());
        }
    }

    private long insert(PlanVersionRow row, String effectiveKey) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO irr_plan_version
                        (group_code, turn_no, version_no, status, available_cubic_meter,
                         request_id, fingerprint, effective_unique_key, published_at, created_at)
                    VALUES (?, ?, ?, 'EFFECTIVE', ?, ?, ?, ?, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.groupCode());
            ps.setLong(2, row.turnNo());
            ps.setInt(3, row.versionNo());
            ps.setLong(4, row.availableCubicMeter());
            ps.setString(5, row.requestId());
            ps.setString(6, row.fingerprint());
            ps.setString(7, effectiveKey);
            ps.setTimestamp(8, Timestamp.from(row.publishedAt()));
            ps.setTimestamp(9, Timestamp.from(row.publishedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入计划版本未返回自增主键");
        }
        return key.longValue();
    }

    private static final RowMapper<PlanVersionRow> VERSION_MAPPER = (ResultSet rs, int rowNum) ->
            new PlanVersionRow(
                    rs.getLong("id"),
                    rs.getString("group_code"),
                    rs.getLong("turn_no"),
                    rs.getInt("version_no"),
                    rs.getString("status"),
                    rs.getLong("available_cubic_meter"),
                    rs.getString("request_id"),
                    rs.getString("fingerprint"),
                    rs.getTimestamp("published_at").toInstant());

    private static final RowMapper<PlanLineRow> LINE_MAPPER = new RowMapper<>() {
        @Override
        public PlanLineRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PlanLineRow(
                    rs.getString("outlet_code"),
                    rs.getLong("area_square_meter"),
                    rs.getBigDecimal("weight"),
                    rs.getLong("plan_cubic_meter"),
                    rs.getLong("carryover_cubic_meter"));
        }
    };
}
