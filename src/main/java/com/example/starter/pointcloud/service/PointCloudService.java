package com.example.starter.pointcloud.service;

import com.example.starter.pointcloud.api.BoxQueryRequest;
import com.example.starter.pointcloud.api.CorridorQueryRequest;
import com.example.starter.pointcloud.api.QueryHitPoint;
import com.example.starter.pointcloud.api.QueryResponse;
import com.example.starter.pointcloud.api.RadiusQueryRequest;
import com.example.starter.pointcloud.api.ReindexResponse;
import com.example.starter.pointcloud.api.WriteChunkRequest;
import com.example.starter.pointcloud.api.WriteChunkResponse;
import com.example.starter.pointcloud.model.BoundingBox;
import com.example.starter.pointcloud.model.ChunkMetadata;
import com.example.starter.pointcloud.model.Compression;
import com.example.starter.pointcloud.model.Corridor;
import com.example.starter.pointcloud.model.Point;
import com.example.starter.pointcloud.model.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 点云块写入、空间查询与索引重建的核心服务。
 *
 * <p>写入按 chunkId 分段加锁，保证同 ID 并发请求的幂等/409 语义；块文件先临时写入、
 * 回读校验后原子发布，随后元数据写入 H2 索引。查询先按包围盒粗过滤，再读块做
 * SHA-256 校验与点级闭区间精过滤；损坏块隔离并从索引删除。重建不清空索引，
 * 仅依据块文件 MERGE，重建期间写入不受阻塞且增量不会遗漏。</p>
 */
public class PointCloudService {

    private static final Logger log = LoggerFactory.getLogger(PointCloudService.class);

    private static final Comparator<QueryHitPoint> HIT_ORDER =
            Comparator.comparingLong(QueryHitPoint::timestampNanos)
                    .thenComparing(QueryHitPoint::chunkId)
                    .thenComparingInt(QueryHitPoint::pointIndex);

    protected final ChunkFileStore fileStore;
    protected final ChunkIndexRepository repository;

    /** 按 chunkId 分段的写入锁。 */
    private final Map<String, Object> chunkLocks = new ConcurrentHashMap<>();

    /** 隔离操作串行化，避免多个查询并发移动同一文件。 */
    private final Object quarantineLock = new Object();

    public PointCloudService(ChunkFileStore fileStore, ChunkIndexRepository repository) {
        this.fileStore = fileStore;
        this.repository = repository;
    }

    /**
     * 写入块：同 ID 同内容重试返回首次结果（deduplicated=true）；同 ID 改参/改内容返回 409。
     */
    public WriteChunkResponse writeChunk(WriteChunkRequest request) {
        String chunkId = request.chunkId();
        Object lock = chunkLocks.computeIfAbsent(chunkId, k -> new Object());
        try {
            synchronized (lock) {
                return doWrite(request);
            }
        } finally {
            chunkLocks.remove(chunkId, lock);
        }
    }

    private WriteChunkResponse doWrite(WriteChunkRequest request) {
        String chunkId = request.chunkId();
        List<Point> points = request.points();

        ChunkMetadata existing = repository.find(chunkId);
        ChunkCodec.Encoded encoded = ChunkCodec.encode(
                chunkId, request.version(), request.source(), points, request.compression());

        if (existing != null) {
            if (existing.checksum().equals(encoded.checksum())) {
                return toWriteResponse(existing, true);
            }
            throw new ChunkConflictException("chunkId=" + chunkId
                    + " 已存在但版本/来源/内容与本次请求不一致");
        }

        String fileName;
        boolean deduplicated;
        try {
            ChunkFileStore.StoredChunk stored = fileStore.publish(chunkId, encoded.fileBytes());
            fileName = stored.fileName();
            deduplicated = !stored.newlyPublished();
        } catch (IOException e) {
            throw new UncheckedIOException("块文件发布失败: " + chunkId, e);
        }

        ChunkMetadata metadata;
        if (deduplicated) {
            // 索引缺失但正式文件已存在（如内存索引重建前）：以文件内容为准校验后补索引。
            try {
                metadata = readValidatedFile(chunkId, fileName).metadata();
            } catch (IOException e) {
                throw new UncheckedIOException("已存在块文件读取失败: " + chunkId, e);
            }
            if (!metadata.checksum().equals(encoded.checksum())) {
                throw new ChunkConflictException("chunkId=" + chunkId
                        + " 已存在不同内容的块文件");
            }
        } else {
            metadata = buildMetadata(chunkId, request.version(), request.source(), points,
                    encoded.compression(), encoded.checksum(), fileName);
        }
        repository.upsert(metadata, System.currentTimeMillis());
        return toWriteResponse(metadata, deduplicated);
    }

    /**
     * 闭区间 Box 查询。
     */
    public QueryResponse queryBox(BoxQueryRequest request) {
        if (request.minX() > request.maxX() || request.minY() > request.maxY()
                || request.minZ() > request.maxZ()) {
            throw new IllegalArgumentException("Box 的最小值不得大于最大值");
        }
        BoundingBox queryBox = new BoundingBox(
                request.minX(), request.minY(), request.minZ(),
                request.maxX(), request.maxY(), request.maxZ());
        return query(queryBox, point ->
                point.x() >= request.minX() && point.x() <= request.maxX()
                        && point.y() >= request.minY() && point.y() <= request.maxY()
                        && point.z() >= request.minZ() && point.z() <= request.maxZ());
    }

    /**
     * 球体 Radius 查询（含球面边界）。
     */
    public QueryResponse queryRadius(RadiusQueryRequest request) {
        double cx = request.centerX();
        double cy = request.centerY();
        double cz = request.centerZ();
        double r = request.radiusMeters();
        double r2 = r * r;
        BoundingBox coarse = new BoundingBox(cx - r, cy - r, cz - r, cx + r, cy + r, cz + r);
        return query(coarse, point -> {
            double dx = point.x() - cx;
            double dy = point.y() - cy;
            double dz = point.z() - cz;
            return dx * dx + dy * dy + dz * dz <= r2;
        });
    }

    /**
     * 折线轨迹走廊查询（含宽度边界）。
     */
    public QueryResponse queryCorridor(CorridorQueryRequest request) {
        if (request.vertices().size() < 2) {
            throw new IllegalArgumentException("折线至少需要 2 个顶点");
        }
        List<Vec3> vertices = request.vertices().stream()
                .map(CorridorQueryRequest.Vec3Holder::toVec3)
                .toList();
        Corridor corridor = new Corridor(vertices, request.halfWidthMeters());
        return query(corridor.boundingBox(),
                point -> corridor.contains(point.x(), point.y(), point.z()));
    }

    /**
     * 从块文件重建索引；重建期间写入不被阻塞，重建完成后期间新增块仍全部在索引中。
     */
    public ReindexResponse reindex() {
        try {
            List<String> initial = fileStore.listChunkFiles();
            Set<String> processed = new HashSet<>();
            List<String> quarantined = new ArrayList<>();
            int reindexed = 0;

            // 第一轮：处理重建开始时已存在的全部块文件。
            for (String fileName : initial) {
                if (processFileForReindex(fileName, quarantined)) {
                    processed.add(fileName);
                    reindexed++;
                }
            }

            // 第二轮：捕获第一轮期间新发布、未在初始快照中的增量文件。
            // 第二轮之后再发布的块由写入路径自行 upsert，索引同样不会缺失；
            // 因此不对“文件缺失”的索引行做对账删除，以免误删重建收尾瞬间并发写入的新块
            //（损坏块的索引行由隔离路径负责删除）。
            List<String> later = fileStore.listChunkFiles();
            for (String fileName : later) {
                if (processed.add(fileName) && processFileForReindex(fileName, quarantined)) {
                    reindexed++;
                }
            }

            int scanned = processed.size();
            return new ReindexResponse(scanned, reindexed, quarantined);
        } catch (IOException e) {
            throw new UncheckedIOException("遍历块目录失败", e);
        }
    }

    /**
     * 重建时处理单个块文件：校验通过则 MERGE 元数据，损坏则隔离。
     *
     * @return true 表示成功写入索引
     */
    private boolean processFileForReindex(String fileName, List<String> quarantined) {
        onBeforeProcessFile(fileName);
        String chunkId = null;
        try {
            byte[] bytes = fileStore.read(fileName);
            ChunkCodec.Decoded decoded = ChunkCodec.decode(bytes);
            chunkId = decoded.chunkId();
            ChunkMetadata metadata = buildMetadata(decoded.chunkId(), decoded.version(),
                    decoded.source(), decoded.points(),
                    compressionOf(bytes), decoded.checksum(), fileName);
            repository.upsert(metadata, System.currentTimeMillis());
            return true;
        } catch (CorruptChunkException | IOException e) {
            String resolvedId = chunkId != null ? chunkId
                    : ChunkFileStore.chunkIdFromFileName(fileName);
            log.warn("重建时发现损坏块 {}: {}", fileName, e.getMessage());
            quarantineQuietly(resolvedId, fileName, "重建校验失败: " + e.getMessage());
            quarantined.add(resolvedId != null ? resolvedId : fileName);
            return false;
        }
    }

    /**
     * 重建每个文件前的回调，默认空实现；测试可注入同步点以模拟重建期间增量写入。
     */
    protected void onBeforeProcessFile(String fileName) {
    }

    /**
     * 查询公共流程：包围盒粗过滤 → 读块校验 → 点级精过滤 → 稳定排序。
     */
    private QueryResponse query(BoundingBox coarseBox, PointPredicate predicate) {
        List<QueryHitPoint> hits = new ArrayList<>();
        List<QueryResponse.QuarantinedChunk> quarantined = new ArrayList<>();

        for (ChunkMetadata metadata : repository.findAll()) {
            if (!metadata.bbox().intersects(coarseBox)) {
                continue;
            }
            LoadedChunk loaded;
            try {
                loaded = readValidatedFile(metadata.chunkId(), metadata.fileName());
            } catch (CorruptChunkException e) {
                quarantineQuietly(metadata.chunkId(), metadata.fileName(), e.getMessage());
                quarantined.add(new QueryResponse.QuarantinedChunk(
                        metadata.chunkId(), metadata.fileName(), e.getMessage()));
                continue;
            } catch (IOException e) {
                quarantineQuietly(metadata.chunkId(), metadata.fileName(),
                        "块文件读取失败: " + e.getMessage());
                quarantined.add(new QueryResponse.QuarantinedChunk(
                        metadata.chunkId(), metadata.fileName(), e.getMessage()));
                continue;
            }
            if (!loaded.metadata().checksum().equals(metadata.checksum())) {
                String reason = "索引校验和与块文件不一致";
                quarantineQuietly(metadata.chunkId(), metadata.fileName(), reason);
                quarantined.add(new QueryResponse.QuarantinedChunk(
                        metadata.chunkId(), metadata.fileName(), reason));
                continue;
            }

            List<Point> points = loaded.decoded().points();
            // 版本与来源取实际读取并通过 SHA-256 校验的文件内容（校验和一致时与索引相同）
            long actualVersion = loaded.metadata().version();
            String actualSource = loaded.metadata().source();
            for (int i = 0; i < points.size(); i++) {
                Point p = points.get(i);
                if (predicate.test(p)) {
                    hits.add(new QueryHitPoint(p.x(), p.y(), p.z(), p.timestampNanos(),
                            metadata.chunkId(), actualVersion, actualSource, i));
                }
            }
        }

        hits.sort(HIT_ORDER);
        return new QueryResponse(hits, quarantined);
    }

    /**
     * 已加载块：解码内容与由文件内容重新计算的元数据。
     */
    private record LoadedChunk(ChunkCodec.Decoded decoded, ChunkMetadata metadata) {
    }

    /**
     * 读取并校验块文件，同时由内容重建元数据。
     */
    private LoadedChunk readValidatedFile(String chunkId, String fileName) throws IOException {
        byte[] bytes = fileStore.read(fileName);
        ChunkCodec.Decoded decoded = ChunkCodec.decode(bytes);
        ChunkMetadata metadata = buildMetadata(decoded.chunkId(), decoded.version(),
                decoded.source(), decoded.points(),
                compressionOf(bytes), decoded.checksum(), fileName);
        return new LoadedChunk(decoded, metadata);
    }

    /**
     * 隔离块并删除索引行；并发情况下仅移动一次。
     */
    private void quarantineQuietly(String chunkId, String fileName, String reason) {
        synchronized (quarantineLock) {
            try {
                if (fileStore.exists(fileName)) {
                    fileStore.quarantine(chunkId, fileName, reason);
                }
                repository.delete(chunkId);
            } catch (IOException e) {
                log.error("隔离块失败 chunkId={} file={}", chunkId, fileName, e);
            }
        }
    }

    /**
     * 由点列表与块属性构造元数据（含时间范围与包围盒）。
     */
    private static ChunkMetadata buildMetadata(String chunkId, long version, String source,
                                               List<Point> points, Compression compression,
                                               String checksum, String fileName) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (Point p : points) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
            start = Math.min(start, p.timestampNanos());
            end = Math.max(end, p.timestampNanos());
        }
        BoundingBox bbox = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        return new ChunkMetadata(chunkId, version, source, start, end, bbox,
                points.size(), compression, checksum, fileName);
    }

    /**
     * 从文件字节头判定实际压缩方式。
     */
    private static Compression compressionOf(byte[] fileBytes) {
        return fileBytes.length > 4 && fileBytes[4] == 1 ? Compression.GZIP : Compression.NONE;
    }

    private static WriteChunkResponse toWriteResponse(ChunkMetadata m, boolean deduplicated) {
        return new WriteChunkResponse(m.chunkId(), m.version(), m.source(), m.pointCount(),
                m.compression(), m.checksum(), deduplicated);
    }

    /**
     * 点级谓词，用于三类查询的精过滤。
     */
    @FunctionalInterface
    private interface PointPredicate {
        boolean test(Point point);
    }
}
