package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 合成处理设施目录的数据访问：设施版本、可接收品类、UTC 停收区间与修改留痕。
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
     * @param version 设施目录版本，自 1 起，每次修改成功 +1
     */
    public record FacilityRow(long id, String code, String name, boolean active, int version) {
    }

    /**
     * 停收区间行：UTC [startAt, endAt)，首端含、末端不含。
     */
    public record SuspensionRow(Instant startAt, Instant endAt) {
    }

    /**
     * 设施修改留痕行（幂等键）。
     */
    public record RevisionRow(long id, long facilityId, String requestId, String fingerprint,
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
     * 乐观版本推进：仅当当前版本等于 expectedVersion 时 +1，返回更新行数。
     */
    public int bumpVersion(long facilityId, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE facility SET version = version + 1
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("id", facilityId)
                .param("expectedVersion", expectedVersion)
                .update();
    }

    /**
     * 设施是否可接收指定品类：无任何品类配置行时兼容旧行为视为全部可收。
     */
    public boolean acceptsCategory(long facilityId, String category) {
        Integer configured = jdbc.sql(
                        "SELECT COUNT(*) FROM facility_category WHERE facility_id = :facilityId")
                .param("facilityId", facilityId)
                .query(Integer.class)
                .single();
        if (configured == 0) {
            return true;
        }
        Integer matched = jdbc.sql("""
                        SELECT COUNT(*) FROM facility_category
                        WHERE facility_id = :facilityId AND category = :category
                        """)
                .param("facilityId", facilityId)
                .param("category", category)
                .query(Integer.class)
                .single();
        return matched > 0;
    }

    public List<String> listCategories(long facilityId) {
        return jdbc.sql("""
                        SELECT category FROM facility_category
                        WHERE facility_id = :facilityId ORDER BY category
                        """)
                .param("facilityId", facilityId)
                .query(String.class)
                .list();
    }

    /**
     * 全量替换设施可接收品类（先清后写，与版本推进同事务）。
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
     * 当前时刻（UTC）是否处于停收区间 [start, end) 内。
     */
    public boolean isSuspendedAt(long facilityId, Instant now) {
        Integer matched = jdbc.sql("""
                        SELECT COUNT(*) FROM facility_suspension
                        WHERE facility_id = :facilityId AND start_at <= :now AND end_at > :now
                        """)
                .param("facilityId", facilityId)
                .param("now", Timestamp.from(now))
                .query(Integer.class)
                .single();
        return matched > 0;
    }

    public List<SuspensionRow> listSuspensions(long facilityId) {
        return jdbc.sql("""
                        SELECT start_at, end_at FROM facility_suspension
                        WHERE facility_id = :facilityId ORDER BY start_at, end_at, id
                        """)
                .param("facilityId", facilityId)
                .query((rs, rowNum) -> new SuspensionRow(
                        rs.getTimestamp("start_at").toInstant(),
                        rs.getTimestamp("end_at").toInstant()))
                .list();
    }

    /**
     * 全量替换设施停收区间（先清后写，与版本推进同事务）。
     */
    public void replaceSuspensions(long facilityId, List<SuspensionRow> suspensions) {
        jdbc.sql("DELETE FROM facility_suspension WHERE facility_id = :facilityId")
                .param("facilityId", facilityId)
                .update();
        for (SuspensionRow row : suspensions) {
            jdbc.sql("INSERT INTO facility_suspension (facility_id, start_at, end_at)"
                            + " VALUES (:facilityId, :startAt, :endAt)")
                    .param("facilityId", facilityId)
                    .param("startAt", Timestamp.from(row.startAt()))
                    .param("endAt", Timestamp.from(row.endAt()))
                    .update();
        }
    }

    /**
     * 记录一次设施修改留痕（幂等键 request_id 由数据库唯一约束保证）。
     */
    public void insertRevision(long facilityId, String requestId, String fingerprint,
                               int version, Instant appliedAt) {
        jdbc.sql("""
                        INSERT INTO facility_revision (facility_id, request_id, fingerprint, version, applied_at)
                        VALUES (:facilityId, :requestId, :fingerprint, :version, :appliedAt)
                        """)
                .param("facilityId", facilityId)
                .param("requestId", requestId)
                .param("fingerprint", fingerprint)
                .param("version", version)
                .param("appliedAt", Timestamp.from(appliedAt))
                .update();
    }

    public Optional<RevisionRow> findRevisionByRequestId(String requestId) {
        return jdbc.sql("""
                        SELECT id, facility_id, request_id, fingerprint, version, applied_at
                        FROM facility_revision WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((rs, rowNum) -> new RevisionRow(
                        rs.getLong("id"),
                        rs.getLong("facility_id"),
                        rs.getString("request_id"),
                        rs.getString("fingerprint"),
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
