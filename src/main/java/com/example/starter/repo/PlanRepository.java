package com.example.starter.repo;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.PlanSegment;
import com.example.starter.domain.PlanVersion;

/**
 * 日前计划聚合数据访问：不可变版本头、96 段明细与当前版本指针。
 * 版本行 SELECT ... FOR UPDATE 与风场行锁共同保证发布与指令受理竞争时版本和基数一致。
 */
@Repository
public class PlanRepository {

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final RowMapper<PlanVersion> VERSION_MAPPER = (rs, n) -> {
        PlanVersion v = new PlanVersion();
        v.setId(rs.getLong("id"));
        v.setFarmCode(rs.getString("farm_code"));
        Date planDate = rs.getDate("plan_date");
        v.setPlanDate(planDate == null ? null : planDate.toLocalDate());
        v.setVersionNo(rs.getInt("version_no"));
        v.setCurrentFlag(rs.getString("current_flag"));
        v.setRequestId(rs.getString("request_id"));
        v.setFingerprint(rs.getString("fingerprint"));
        v.setPublishedAt(rs.getTimestamp("published_at").toInstant());
        return v;
    };

    static final RowMapper<PlanSegment> SEGMENT_MAPPER = (rs, n) -> {
        PlanSegment s = new PlanSegment();
        s.setId(rs.getLong("id"));
        s.setVersionId(rs.getLong("version_id"));
        s.setSegmentIndex(rs.getInt("segment_index"));
        s.setTurbineId(rs.getLong("turbine_id"));
        s.setPowerKw(rs.getInt("power_kw"));
        return s;
    };

    /** 当前最大版本号；从未发布时返回 null。须在风场行锁内调用。 */
    public Integer findMaxVersionNo(String farmCode, LocalDate planDate) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(version_no) FROM day_ahead_plan_version WHERE farm_code = ? AND plan_date = ?",
                Integer.class, farmCode, Date.valueOf(planDate));
        return max;
    }

    public PlanVersion findVersion(String farmCode, LocalDate planDate, int versionNo) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM day_ahead_plan_version WHERE farm_code = ? AND plan_date = ? AND version_no = ?",
                    VERSION_MAPPER, farmCode, Date.valueOf(planDate), versionNo);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public PlanVersion findVersionById(long versionId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM day_ahead_plan_version WHERE id = ?", VERSION_MAPPER, versionId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public PlanVersion findCurrentVersion(String farmCode, LocalDate planDate) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM day_ahead_plan_version WHERE farm_code = ? AND plan_date = ? AND current_flag = 'Y'",
                    VERSION_MAPPER, farmCode, Date.valueOf(planDate));
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 锁定指定版本行（FOR UPDATE），不存在返回 null；用于指令受理时冻结版本。 */
    public PlanVersion lockVersion(String farmCode, LocalDate planDate, int versionNo) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM day_ahead_plan_version WHERE farm_code = ? AND plan_date = ? AND version_no = ? FOR UPDATE",
                    VERSION_MAPPER, farmCode, Date.valueOf(planDate), versionNo);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<PlanVersion> findVersions(String farmCode, LocalDate planDate) {
        return jdbc.query(
                "SELECT * FROM day_ahead_plan_version WHERE farm_code = ? AND plan_date = ? ORDER BY version_no",
                VERSION_MAPPER, farmCode, Date.valueOf(planDate));
    }

    /**
     * 插入新版本（current_flag 暂为 NULL），返回 id；随后由 {@link #swapCurrent} 原子替换当前指针。
     */
    public long insertVersion(PlanVersion version) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO day_ahead_plan_version
                        (farm_code, plan_date, version_no, current_flag, request_id, fingerprint, published_at)
                    VALUES (?, ?, ?, NULL, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, version.getFarmCode());
            ps.setDate(2, Date.valueOf(version.getPlanDate()));
            ps.setInt(3, version.getVersionNo());
            ps.setString(4, version.getRequestId());
            ps.setString(5, version.getFingerprint());
            ps.setTimestamp(6, Timestamp.from(version.getPublishedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        version.setId(id);
        return id;
    }

    /**
     * 同一事务内原子替换当前指针：旧当前版本置 NULL，新版本置 'Y'。
     * 唯一索引 uk_plan_current 兜底保证同场同日至多一个当前版本。
     */
    public void swapCurrent(String farmCode, LocalDate planDate, long newVersionId) {
        jdbc.update(
                "UPDATE day_ahead_plan_version SET current_flag = NULL "
                        + "WHERE farm_code = ? AND plan_date = ? AND current_flag = 'Y'",
                farmCode, Date.valueOf(planDate));
        jdbc.update("UPDATE day_ahead_plan_version SET current_flag = 'Y' WHERE id = ?",
                newVersionId);
    }

    public void insertSegments(long versionId, List<PlanSegment> segments) {
        jdbc.batchUpdate("""
                INSERT INTO day_ahead_plan_segment (version_id, segment_index, turbine_id, power_kw)
                VALUES (?, ?, ?, ?)
                """,
                segments, segments.size(), (ps, s) -> {
                    ps.setLong(1, versionId);
                    ps.setInt(2, s.getSegmentIndex());
                    ps.setLong(3, s.getTurbineId());
                    ps.setInt(4, s.getPowerKw());
                });
    }

    public List<PlanSegment> findSegments(long versionId) {
        return jdbc.query(
                "SELECT * FROM day_ahead_plan_segment WHERE version_id = ? ORDER BY segment_index, turbine_id",
                SEGMENT_MAPPER, versionId);
    }

    public List<PlanSegment> findSegments(long versionId, int segmentIndex) {
        return jdbc.query(
                "SELECT * FROM day_ahead_plan_segment WHERE version_id = ? AND segment_index = ? ORDER BY turbine_id",
                SEGMENT_MAPPER, versionId, segmentIndex);
    }
}
