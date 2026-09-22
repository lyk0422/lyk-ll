package com.example.starter.costmap.repo;

import com.example.starter.costmap.model.MapVersion;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 不可变地图版本持久化（H2/MySQL 兼容 SQL）。
 */
@Repository
public class MapVersionRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<MapVersion> MAPPER = (rs, n) -> new MapVersion(
            rs.getString("version_id"),
            rs.getInt("width"),
            rs.getInt("height"),
            rs.getDouble("resolution"),
            rs.getDouble("inflation_radius"),
            rs.getDouble("cost_decay"),
            rs.getBytes("static_grid"),
            rs.getBytes("static_inflated"),
            rs.getLong("seq"));

    public MapVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入新版本；版本ID 冲突时抛 {@link DuplicateKeyException}。 */
    public void insert(MapVersion version) {
        jdbc.update("""
                INSERT INTO map_version
                    (version_id, width, height, resolution, inflation_radius, cost_decay,
                     static_grid, static_inflated, seq)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                version.versionId(),
                version.width(),
                version.height(),
                version.resolution(),
                version.inflationRadius(),
                version.costDecay(),
                version.staticGrid(),
                version.staticInflated(),
                version.seq());
    }

    /** 按ID读取版本，不存在返回 empty。 */
    public Optional<MapVersion> findById(String versionId) {
        return jdbc.query("""
                SELECT version_id, width, height, resolution, inflation_radius, cost_decay,
                       static_grid, static_inflated, seq
                  FROM map_version WHERE version_id = ?
                """, MAPPER, versionId).stream().findFirst();
    }

    /**
     * 加行锁读取版本，序列化同一版本上的并发更新（不同版本仍可并行）；事务提交时释放。
     */
    public Optional<MapVersion> findByIdForUpdate(String versionId) {
        return jdbc.query("""
                SELECT version_id, width, height, resolution, inflation_radius, cost_decay,
                       static_grid, static_inflated, seq
                  FROM map_version WHERE version_id = ? FOR UPDATE
                """, MAPPER, versionId).stream().findFirst();
    }

    /**
     * 乐观自增 seq；仅当当前 seq 等于 expected 时成功。
     *
     * @return 自增后的 seq（expected + 1），失败返回 -1
     */
    public long compareAndIncrementSeq(String versionId, long expected) {
        int updated = jdbc.update(
                "UPDATE map_version SET seq = seq + 1 WHERE version_id = ? AND seq = ?",
                versionId, expected);
        return updated == 1 ? expected + 1 : -1;
    }
}
