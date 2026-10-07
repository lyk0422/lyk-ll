package com.example.starter.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 分车出厂数据访问层：车单主表与头只活跃占用，全部参数化 SQL，时间以 UTC ISO-8601 字符串存取。
 */
@Repository
public class TruckRepository {

    private final JdbcTemplate jdbc;

    public TruckRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 分车清单行。frozenHeads 为创建时给定顺序的 JSON 数组文本。 */
    public record ManifestRow(long id, long batchId, String truckNo, int version, String status,
                              int headCount, String frozenHeads, String createdAt,
                              String revokedAt, String dispatchedAt) {
    }

    /** 头只活跃占用行：同一批次同一头只至多一行。 */
    public record OccupationRow(long id, long batchId, long manifestId, String headNo, String status,
                                String createdAt) {
    }

    private static final RowMapper<ManifestRow> MANIFEST_MAPPER = (rs, n) -> new ManifestRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("truck_no"), rs.getInt("version"),
            rs.getString("status"), rs.getInt("head_count"), rs.getString("frozen_heads"),
            rs.getString("created_at"), rs.getString("revoked_at"), rs.getString("dispatched_at"));

    private static final RowMapper<OccupationRow> OCCUPATION_MAPPER = (rs, n) -> new OccupationRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("manifest_id"),
            rs.getString("head_no"), rs.getString("status"), rs.getString("created_at"));

    // ---------- 车单主表 ----------

    /** 创建车单（版本 1、RESERVED），返回生成主键。 */
    public long insertManifest(long batchId, String truckNo, int headCount, String frozenHeadsJson,
                               String createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO truck_manifest(batch_id, truck_no, version, status, head_count,"
                            + " frozen_heads, created_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, truckNo);
            ps.setInt(3, 1);
            ps.setString(4, "RESERVED");
            ps.setInt(5, headCount);
            ps.setString(6, frozenHeadsJson);
            ps.setString(7, createdAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("车单主键生成失败");
        }
        return key.longValue();
    }

    public Optional<ManifestRow> findManifestByTruck(long batchId, String truckNo) {
        List<ManifestRow> rows = jdbc.query(
                "SELECT * FROM truck_manifest WHERE batch_id = ? AND truck_no = ?",
                MANIFEST_MAPPER, batchId, truckNo);
        return rows.stream().findFirst();
    }

    /** 行锁读取车单（须在事务内调用）。 */
    public Optional<ManifestRow> findManifestByTruckForUpdate(long batchId, String truckNo) {
        List<ManifestRow> rows = jdbc.query(
                "SELECT * FROM truck_manifest WHERE batch_id = ? AND truck_no = ? FOR UPDATE",
                MANIFEST_MAPPER, batchId, truckNo);
        return rows.stream().findFirst();
    }

    public List<ManifestRow> findManifests(long batchId) {
        return jdbc.query("SELECT * FROM truck_manifest WHERE batch_id = ? ORDER BY truck_no",
                MANIFEST_MAPPER, batchId);
    }

    /** 批次是否已存在任意车单（含已撤销），用于判定分车模式已启用。 */
    public int countManifests(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM truck_manifest WHERE batch_id = ?", Integer.class, batchId);
        return count == null ? 0 : count;
    }

    /** RESERVED→REVOKED 且版本 expectedVersion→expectedVersion+1，返回受影响行数。 */
    public int markRevoked(long manifestId, int expectedVersion, String revokedAt) {
        return jdbc.update("UPDATE truck_manifest SET status = 'REVOKED', version = version + 1,"
                        + " revoked_at = ? WHERE id = ? AND status = 'RESERVED' AND version = ?",
                revokedAt, manifestId, expectedVersion);
    }

    /** RESERVED→RELEASED 且版本 expectedVersion→expectedVersion+1，返回受影响行数。 */
    public int markDispatched(long manifestId, int expectedVersion, String dispatchedAt) {
        return jdbc.update("UPDATE truck_manifest SET status = 'RELEASED', version = version + 1,"
                        + " dispatched_at = ? WHERE id = ? AND status = 'RESERVED' AND version = ?",
                dispatchedAt, manifestId, expectedVersion);
    }

    // ---------- 头只活跃占用 ----------

    public void insertOccupation(long batchId, long manifestId, String headNo, String status,
                                 String createdAt) {
        jdbc.update("INSERT INTO truck_manifest_head(batch_id, manifest_id, head_no, status, created_at)"
                        + " VALUES (?,?,?,?,?)",
                batchId, manifestId, headNo, status, createdAt);
    }

    public List<OccupationRow> findOccupations(long batchId) {
        return jdbc.query("SELECT * FROM truck_manifest_head WHERE batch_id = ? ORDER BY head_no",
                OCCUPATION_MAPPER, batchId);
    }

    /** 删除指定车单的全部活跃占用（撤销释放），返回删除行数。 */
    public int deleteOccupationsByManifest(long manifestId) {
        return jdbc.update("DELETE FROM truck_manifest_head WHERE manifest_id = ?", manifestId);
    }

    /** 指定车单占用 RESERVED→RELEASED（发车核销），返回受影响行数。 */
    public int markOccupationsReleased(long manifestId) {
        return jdbc.update("UPDATE truck_manifest_head SET status = 'RELEASED' WHERE manifest_id = ?"
                + " AND status = 'RESERVED'", manifestId);
    }
}
