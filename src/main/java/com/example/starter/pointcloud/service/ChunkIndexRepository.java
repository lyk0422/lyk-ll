package com.example.starter.pointcloud.service;

import com.example.starter.pointcloud.model.BoundingBox;
import com.example.starter.pointcloud.model.ChunkMetadata;
import com.example.starter.pointcloud.model.Compression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 块索引仓库：基于 H2（MODE=MySQL）保存块元数据，并提供空间/时间粗过滤查询。
 *
 * <p>所有查询在 SQL 层使用闭区间比较（{@code <=} / {@code >=}），与点级精过滤的
 * 闭区间语义一致；包围盒相交判断在 Java 侧完成以避免数据库方言差异。</p>
 */
public class ChunkIndexRepository {

    private static final String UPSERT_SQL = """
            MERGE INTO pc_chunk (
                chunk_id, version, source, start_nanos, end_nanos,
                min_x, min_y, min_z, max_x, max_y, max_z,
                point_count, compression, checksum, file_name, created_at
            ) KEY(chunk_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private static final String SELECT_ALL_SQL = """
            SELECT chunk_id, version, source, start_nanos, end_nanos,
                   min_x, min_y, min_z, max_x, max_y, max_z,
                   point_count, compression, checksum, file_name
            FROM pc_chunk
            """;

    private final JdbcTemplate jdbc;

    public ChunkIndexRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * 插入或更新块元数据（按 chunk_id 主键合并）。
     */
    public void upsert(ChunkMetadata m, long createdAtMillis) {
        jdbc.update(UPSERT_SQL,
                m.chunkId(), m.version(), m.source(), m.startNanos(), m.endNanos(),
                m.bbox().minX(), m.bbox().minY(), m.bbox().minZ(),
                m.bbox().maxX(), m.bbox().maxY(), m.bbox().maxZ(),
                m.pointCount(), m.compression().name(), m.checksum(), m.fileName(),
                createdAtMillis);
    }

    /**
     * 按 chunkId 查询，不存在返回 {@code null}。
     */
    public ChunkMetadata find(String chunkId) {
        List<ChunkMetadata> list = jdbc.query(
                SELECT_ALL_SQL + " WHERE chunk_id = ?", MAPPER, chunkId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 返回全部块元数据。
     */
    public List<ChunkMetadata> findAll() {
        return jdbc.query(SELECT_ALL_SQL + " ORDER BY chunk_id", MAPPER);
    }

    /**
     * 返回时间范围与 {@code [startNanos, endNanos]} 闭区间相交的全部块；
     * 空间过滤由调用方按包围盒完成。
     */
    public List<ChunkMetadata> findByTimeRange(long startNanos, long endNanos) {
        return jdbc.query(
                SELECT_ALL_SQL
                        + " WHERE start_nanos <= ? AND end_nanos >= ? ORDER BY chunk_id",
                MAPPER, endNanos, startNanos);
    }

    /**
     * 删除块索引记录（隔离时使用）。
     */
    public void delete(String chunkId) {
        jdbc.update("DELETE FROM pc_chunk WHERE chunk_id = ?", chunkId);
    }

    /**
     * 当前索引中的块数。
     */
    public int count() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM pc_chunk", Integer.class);
        return c == null ? 0 : c;
    }

    private static final RowMapper<ChunkMetadata> MAPPER = new ChunkRowMapper();

    private static final class ChunkRowMapper implements RowMapper<ChunkMetadata> {
        @Override
        public ChunkMetadata mapRow(ResultSet rs, int rowNum) throws SQLException {
            BoundingBox bbox = new BoundingBox(
                    rs.getDouble("min_x"), rs.getDouble("min_y"), rs.getDouble("min_z"),
                    rs.getDouble("max_x"), rs.getDouble("max_y"), rs.getDouble("max_z"));
            return new ChunkMetadata(
                    rs.getString("chunk_id"),
                    rs.getLong("version"),
                    rs.getString("source"),
                    rs.getLong("start_nanos"),
                    rs.getLong("end_nanos"),
                    bbox,
                    rs.getInt("point_count"),
                    Compression.valueOf(rs.getString("compression")),
                    rs.getString("checksum"),
                    rs.getString("file_name"));
        }
    }
}
