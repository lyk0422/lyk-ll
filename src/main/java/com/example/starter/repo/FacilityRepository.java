package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 合成处理设施目录的数据访问：设施版本、可接收品类、UTC 停收区间与改版留痕。
 */
@Repository
public class FacilityRepository {

    private final JdbcClient jdbc;

    public FacilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 处理设施行。
     *
     * @param id      设施 id
     * @param code    设施编码
     * @param name    设施名称
     * @param active  是否在目录内有效
     * @param version 设施版本，1 起，每次成功改版加 1
     */
    public record FacilityRow(long id, String code, String name, boolean active, int version) {
    }

    /**
     * 停收区间行。UTC 半开区间 [startAt, endAt)：首端停收、恰 endAt 恢复。
     */
    public record SuspensionRow(long facilityId, Instant startAt, Instant endAt) {
    }

    /**
     * 设施改版留痕行。
     */
    public record RevisionRow(long id, String requestId, String fingerprint, long facilityId,
                              int version, Instant appliedAt) {
    }

    public Optional<FacilityRow> findByCode(String code) {
        return jdbc.sql("SELECT id, code, name, active, version FROM facility WHERE code = :code")
                .param("code", code)
                .query(this::mapFacility)
                .optional();
    }

    public Optional<FacilityRow> findById(long id) {
        return jdbc.sql("SELECT id, code, name, active, version FROM facility WHERE id = :id")
                .param("id", id)
                .query(this::mapFacility)
                .optional();
    }

    /**
     * 设施已配置的可接收品类；空列表表示未配置（不限制品类）。
     */
    public List<String> findCategories(long facilityId) {
        return jdbc.sql("SELECT category FROM facility_category WHERE facility_id = :facilityId ORDER BY category")
                .param("facilityId", facilityId)
                .query(String.class)
                .list();
    }

    public Optional<SuspensionRow> findSuspension(long facilityId) {
        return jdbc.sql("SELECT facility_id, start_at, end_at FROM facility_suspension WHERE facility_id = :facilityId")
                .param("facilityId", facilityId)
                .query((rs, rowNum) -> new SuspensionRow(
                        rs.getLong("facility_id"),
                        rs.getTimestamp("start_at").toInstant(),
                        rs.getTimestamp("end_at").toInstant()))
                .optional();
    }

    /**
     * 条件改版：仅当当前版本等于 expectedVersion 时推进版本并按需更新有效标记，返回更新行数。
     */
    public int reviseFacility(long facilityId, int expectedVersion, Boolean active) {
        return jdbc.sql("""
                        UPDATE facility
                        SET version = version + 1,
                            active = COALESCE(:active, active)
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("active", active == null ? null : (active ? 1 : 0))
                .param("id", facilityId)
                .param("expectedVersion", expectedVersion)
                .update();
    }

    /**
     * 整体替换设施可接收品类（先清后插，与改版同事务）。
     */
    public void replaceCategories(long facilityId, List<String> categories) {
        jdbc.sql("DELETE FROM facility_category WHERE facility_id = :facilityId")
                .param("facilityId", facilityId)
                .update();
        for (String category : categories) {
            jdbc.sql("INSERT INTO facility_category (facility_id, category) VALUES (:facilityId, :category)")
                    .param("facilityId", facilityId)
                    .param("category", category)
                    .update();
        }
    }

    /**
     * 写入或替换设施停收区间（每设施至多一条）。
     */
    public void upsertSuspension(long facilityId, Instant startAt, Instant endAt) {
        jdbc.sql("DELETE FROM facility_suspension WHERE facility_id = :facilityId")
                .param("facilityId", facilityId)
                .update();
        jdbc.sql("INSERT INTO facility_suspension (facility_id, start_at, end_at) VALUES (:facilityId, :startAt, :endAt)")
                .param("facilityId", facilityId)
                .param("startAt", Timestamp.from(startAt))
                .param("endAt", Timestamp.from(endAt))
                .update();
    }

    public void clearSuspension(long facilityId) {
        jdbc.sql("DELETE FROM facility_suspension WHERE facility_id = :facilityId")
                .param("facilityId", facilityId)
                .update();
    }

    /**
     * 插入设施改版留痕并返回自增 id；request_id 唯一约束由数据库保证。
     */
    public long insertRevision(String requestId, String fingerprint, long facilityId,
                               int version, Instant appliedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO facility_revision (request_id, fingerprint, facility_id, version, applied_at)
                        VALUES (:requestId, :fingerprint, :facilityId, :version, :appliedAt)
                        """)
                .param("requestId", requestId)
                .param("fingerprint", fingerprint)
                .param("facilityId", facilityId)
                .param("version", version)
                .param("appliedAt", Timestamp.from(appliedAt))
                .update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("设施改版留痕插入未返回自增主键");
        }
        return key.longValue();
    }

    public Optional<RevisionRow> findRevisionByRequestId(String requestId) {
        return jdbc.sql("""
                        SELECT id, request_id, fingerprint, facility_id, version, applied_at
                        FROM facility_revision WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((rs, rowNum) -> new RevisionRow(
                        rs.getLong("id"),
                        rs.getString("request_id"),
                        rs.getString("fingerprint"),
                        rs.getLong("facility_id"),
                        rs.getInt("version"),
                        rs.getTimestamp("applied_at").toInstant()))
                .optional();
    }

    private FacilityRow mapFacility(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FacilityRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("active") == 1,
                rs.getInt("version"));
    }
}
