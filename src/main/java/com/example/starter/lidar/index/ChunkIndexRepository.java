package com.example.starter.lidar.index;

import com.example.starter.lidar.model.BBox;
import com.example.starter.lidar.model.ChunkMetadata;
import com.example.starter.lidar.model.Compression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 块元数据索引仓库（H2 内存库，MODE=MySQL）。
 * <p>写入以 chunk_id 为主键；重建时按文件快照“不存在才插入”，
 * 使重建扫描期间的并发写入不被覆盖或丢失。</p>
 */
@Repository
public class ChunkIndexRepository {

    private final JdbcTemplate jdbc;

    public ChunkIndexRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ChunkMetadata> MAPPER = (rs, n) -> new ChunkMetadata(
            rs.getString("chunk_id"),
            rs.getLong("version"),
            rs.getString("source"),
            rs.getLong("start_ts"),
            rs.getLong("end_ts"),
            new BBox(
                    rs.getDouble("min_x"), rs.getDouble("min_y"), rs.getDouble("min_z"),
                    rs.getDouble("max_x"), rs.getDouble("max_y"), rs.getDouble("max_z")),
            rs.getInt("point_count"),
            Compression.valueOf(rs.getString("compression")),
            rs.getString("checksum"),
            rs.getString("file_name"),
            rs.getLong("stored_size"),
            rs.getTimestamp("created_at").toInstant());

    /** 建表（幂等）。 */
    public void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS lidar_chunk (
                    chunk_id    VARCHAR(255) PRIMARY KEY COMMENT '块唯一标识',
                    version     BIGINT       NOT NULL COMMENT '块版本（非负）',
                    source      VARCHAR(255) NOT NULL COMMENT '数据来源',
                    start_ts    BIGINT       NOT NULL COMMENT '最早点时间 epoch 毫秒（含）',
                    end_ts      BIGINT       NOT NULL COMMENT '最晚点时间 epoch 毫秒（含）',
                    min_x       DOUBLE       NOT NULL COMMENT '包围盒 X 下界（含）',
                    min_y       DOUBLE       NOT NULL COMMENT '包围盒 Y 下界（含）',
                    min_z       DOUBLE       NOT NULL COMMENT '包围盒 Z 下界（含）',
                    max_x       DOUBLE       NOT NULL COMMENT '包围盒 X 上界（含）',
                    max_y       DOUBLE       NOT NULL COMMENT '包围盒 Y 上界（含）',
                    max_z       DOUBLE       NOT NULL COMMENT '包围盒 Z 上界（含）',
                    point_count INT          NOT NULL COMMENT '块内点数',
                    compression VARCHAR(16)  NOT NULL COMMENT '实际落盘压缩方式 NONE/GZIP',
                    checksum    CHAR(64)     NOT NULL COMMENT '规范化未压缩内容 SHA-256 十六进制',
                    file_name   VARCHAR(512) NOT NULL COMMENT '块文件名（相对块目录）',
                    stored_size BIGINT       NOT NULL COMMENT '落盘字节数',
                    created_at  TIMESTAMP    NOT NULL COMMENT '索引创建时间'
                )
                """);
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_lidar_chunk_file ON lidar_chunk(file_name)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_lidar_chunk_time ON lidar_chunk(start_ts, end_ts)");
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_lidar_chunk_box ON lidar_chunk(
                    min_x, max_x, min_y, max_y, min_z, max_z)
                """);
    }

    /** 按主键查元数据。 */
    public Optional<ChunkMetadata> findById(String chunkId) {
        return jdbc.query("SELECT * FROM lidar_chunk WHERE chunk_id = ?", MAPPER, chunkId)
                .stream().findFirst();
    }

    /**
     * 粗筛候选块：包围盒与查询盒闭区间相交且时间范围重叠（均含边界）。
     */
    public List<ChunkMetadata> findCandidates(BBox box, Long startTs, Long endTs) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM lidar_chunk
                WHERE min_x <= ? AND max_x >= ?
                  AND min_y <= ? AND max_y >= ?
                  AND min_z <= ? AND max_z >= ?
                """);
        List<Object> args = new java.util.ArrayList<>(List.of(
                box.maxX(), box.minX(),
                box.maxY(), box.minY(),
                box.maxZ(), box.minZ()));
        if (startTs != null) {
            sql.append(" AND end_ts >= ?");
            args.add(startTs);
        }
        if (endTs != null) {
            sql.append(" AND start_ts <= ?");
            args.add(endTs);
        }
        sql.append(" ORDER BY chunk_id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 插入新块；主键冲突时由调用方按幂等/冲突处理。 */
    public void insert(ChunkMetadata md) {
        jdbc.update("""
                        INSERT INTO lidar_chunk (chunk_id, version, source, start_ts, end_ts,
                            min_x, min_y, min_z, max_x, max_y, max_z,
                            point_count, compression, checksum, file_name, stored_size, created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                md.chunkId(), md.version(), md.source(), md.startTs(), md.endTs(),
                md.bbox().minX(), md.bbox().minY(), md.bbox().minZ(),
                md.bbox().maxX(), md.bbox().maxY(), md.bbox().maxZ(),
                md.pointCount(), md.compression().name(), md.checksum(),
                md.fileName(), md.storedSize(), Timestamp.from(md.createdAt()));
    }

    /**
     * 重建用：仅当 chunk_id 不存在时插入（保留重建期间并发写入的行）。
     *
     * @return 实际插入行数
     */
    public int insertIfAbsent(ChunkMetadata md) {
        return jdbc.update("""
                        INSERT INTO lidar_chunk (chunk_id, version, source, start_ts, end_ts,
                            min_x, min_y, min_z, max_x, max_y, max_z,
                            point_count, compression, checksum, file_name, stored_size, created_at)
                        SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
                        WHERE NOT EXISTS (SELECT 1 FROM lidar_chunk WHERE chunk_id = ?)
                        """,
                md.chunkId(), md.version(), md.source(), md.startTs(), md.endTs(),
                md.bbox().minX(), md.bbox().minY(), md.bbox().minZ(),
                md.bbox().maxX(), md.bbox().maxY(), md.bbox().maxZ(),
                md.pointCount(), md.compression().name(), md.checksum(),
                md.fileName(), md.storedSize(), Timestamp.from(md.createdAt()),
                md.chunkId());
    }

    /** 全部已索引块 ID（重建开始时快照，用于区分存量行与重建期间增量行）。 */
    public java.util.List<String> findAllChunkIds() {
        return jdbc.queryForList("SELECT chunk_id FROM lidar_chunk", String.class);
    }

    /**
     * 重建清理：仅删除“重建开始前已存在”且文件名不在现存快照集合内的行；
     * 重建期间新写入块的 chunkId 不在 preExistingChunkIds 中，一律保留，避免遗漏。
     *
     * @return 实际删除行数
     */
    public int deleteStaleAmong(Collection<String> preExistingChunkIds,
                                Collection<String> existingFileNames) {
        if (preExistingChunkIds.isEmpty()) {
            return 0;
        }
        String idPlaceholders = String.join(",",
                preExistingChunkIds.stream().map(f -> "?").toList());
        List<Object> args = new java.util.ArrayList<>(preExistingChunkIds);
        if (existingFileNames.isEmpty()) {
            return jdbc.update(
                    "DELETE FROM lidar_chunk WHERE chunk_id IN (" + idPlaceholders + ")",
                    args.toArray());
        }
        String filePlaceholders = String.join(",",
                existingFileNames.stream().map(f -> "?").toList());
        args.addAll(existingFileNames);
        return jdbc.update(
                "DELETE FROM lidar_chunk WHERE chunk_id IN (" + idPlaceholders
                        + ") AND file_name NOT IN (" + filePlaceholders + ")",
                args.toArray());
    }

    /** 隔离块时同步移除其索引行。 */
    public int deleteByChunkId(String chunkId) {
        return jdbc.update("DELETE FROM lidar_chunk WHERE chunk_id = ?", chunkId);
    }

    /** 当前索引块数。 */
    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM lidar_chunk", Long.class);
        return c == null ? 0 : c;
    }
}
