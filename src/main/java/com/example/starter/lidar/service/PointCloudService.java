package com.example.starter.lidar.service;

import com.example.starter.lidar.ChunkConflictException;
import com.example.starter.lidar.audit.AuditRepository;
import com.example.starter.lidar.index.ChunkIndexRepository;
import com.example.starter.lidar.model.ChunkMetadata;
import com.example.starter.lidar.model.Compression;
import com.example.starter.lidar.model.PointDatum;
import com.example.starter.lidar.model.QueryPoint;
import com.example.starter.lidar.model.RebuildResponse;
import com.example.starter.lidar.model.SpatialQueryRequest;
import com.example.starter.lidar.model.SpatialQueryResponse;
import com.example.starter.lidar.model.WriteChunkRequest;
import com.example.starter.lidar.model.WriteChunkResponse;
import com.example.starter.lidar.spatial.SpatialShape;
import com.example.starter.lidar.store.ChunkFileStore;
import com.example.starter.lidar.store.CorruptChunkException;
import com.example.starter.lidar.store.PointCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 点云块核心服务：原子写入与幂等/冲突判定、空间查询与损坏隔离、索引无遗漏重建。
 */
@Service
public class PointCloudService {

    /** 审计事件：查询读取时发现损坏并隔离。 */
    public static final String EVENT_QUARANTINED = "QUARANTINED";
    /** 审计事件：重建扫描发现损坏文件。 */
    public static final String EVENT_REBUILD_BAD = "REBUILD_BAD";

    private static final Logger log = LoggerFactory.getLogger(PointCloudService.class);

    private final ChunkIndexRepository index;
    private final ChunkFileStore store;
    private final AuditRepository audit;
    private final Clock clock;

    /** 按 chunkId 分段加锁，串行化同块并发写入。 */
    private final ConcurrentHashMap<String, ReentrantLock> writeLocks = new ConcurrentHashMap<>();

    public PointCloudService(ChunkIndexRepository index, ChunkFileStore store,
                             AuditRepository audit, Clock lidarClock) {
        this.index = index;
        this.store = store;
        this.audit = audit;
        this.clock = lidarClock;
    }

    /**
     * 写入一个块：
     * 同 ID 且规范化内容/版本/来源一致时幂等返回原结果；同 ID 改参返回 409。
     * 先抢索引主键再原子发布文件，发布失败回滚索引行。
     */
    public WriteChunkResponse write(WriteChunkRequest req) {
        List<PointDatum> points = List.copyOf(req.points());
        byte[] normalized = PointCodec.encode(points);
        String checksum = ChunkFileStore.sha256Hex(normalized);

        Optional<ChunkMetadata> existing = index.findById(req.chunkId());
        if (existing.isPresent()) {
            return respondExisting(existing.get(), req, checksum);
        }

        ReentrantLock lock = writeLocks.computeIfAbsent(req.chunkId(), k -> new ReentrantLock());
        lock.lock();
        try {
            // 双检：同 ID 并发写入可能已落库
            Optional<ChunkMetadata> again = index.findById(req.chunkId());
            if (again.isPresent()) {
                return respondExisting(again.get(), req, checksum);
            }
            ChunkFileStore.StoredBlob blob;
            try {
                blob = store.publish(req.chunkId(), req.version(), req.source(),
                        points, normalized, checksum, req.compression());
            } catch (IOException e) {
                throw new IllegalStateException("块文件发布失败: " + req.chunkId(), e);
            }
            ChunkMetadata md = new ChunkMetadata(
                    req.chunkId(), req.version(), req.source(),
                    timeRange(points).startTs(), timeRange(points).endTs(),
                    boundingBox(points), points.size(), blob.compression(),
                    checksum, blob.fileName(), blob.storedSize(), Instant.now(clock));
            try {
                index.insert(md);
            } catch (DuplicateKeyException dup) {
                // 极端竞态兜底：重读按幂等/冲突处理；新文件内容等价则保留，否则交由 409
                ChunkMetadata winner = index.findById(req.chunkId()).orElseThrow();
                return respondExisting(winner, req, checksum);
            }
            return new WriteChunkResponse(req.chunkId(), req.version(), req.source(),
                    blob.compression(), checksum, points.size(), blob.storedSize(), false);
        } finally {
            lock.unlock();
        }
    }

    private WriteChunkResponse respondExisting(ChunkMetadata md, WriteChunkRequest req,
                                               String incomingChecksum) {
        boolean sameContent = incomingChecksum.equals(md.checksum());
        boolean sameParams = md.version() == req.version() && md.source().equals(req.source());
        if (sameContent && sameParams) {
            return new WriteChunkResponse(md.chunkId(), md.version(), md.source(),
                    md.compression(), md.checksum(), md.pointCount(), md.storedSize(), true);
        }
        throw new ChunkConflictException(
                "chunkId 已存在但内容或参数不一致: " + req.chunkId());
    }

    /**
     * 空间查询：块包围盒+时间粗筛 → 逐块读取校验 → 点级闭区间判定 →
     * 按 (时间, chunkId, 块内点序) 稳定排序。损坏块隔离、审计并从结果排除。
     */
    public SpatialQueryResponse query(SpatialQueryRequest req) {
        SpatialShape shape = SpatialShape.from(req);
        List<ChunkMetadata> candidates =
                index.findCandidates(shape.envelope(), req.startTs(), req.endTs());

        List<QueryPoint> hits = new ArrayList<>();
        List<String> quarantined = new ArrayList<>();
        List<String> scanned = new ArrayList<>();

        for (ChunkMetadata md : candidates) {
            scanned.add(md.chunkId());
            ChunkFileStore.LoadedBlob blob;
            try {
                blob = store.load(md);
            } catch (CorruptChunkException e) {
                log.warn("块损坏，执行隔离: {} ({})", md.chunkId(), e.getMessage());
                quarantine(md, e.getMessage());
                quarantined.add(md.chunkId());
                continue;
            }
            List<PointDatum> points = PointCodec.decode(blob.normalized());
            for (int i = 0; i < points.size(); i++) {
                PointDatum p = points.get(i);
                if (inTimeRange(p.t(), req.startTs(), req.endTs())
                        && shape.contains(p.x(), p.y(), p.z())) {
                    hits.add(new QueryPoint(p.x(), p.y(), p.z(), p.t(),
                            md.chunkId(), md.version(), md.source(), i));
                }
            }
        }

        hits.sort(Comparator
                .comparingLong(QueryPoint::t)
                .thenComparing(QueryPoint::chunkId)
                .thenComparingInt(QueryPoint::pointIndex));
        quarantined.sort(Comparator.naturalOrder());
        return new SpatialQueryResponse(List.copyOf(hits),
                List.copyOf(quarantined), List.copyOf(scanned));
    }

    /**
     * 从块文件重建索引，不阻塞并发写入：
     * 快照后“不存在才插入”，清理阶段只删除重建开始前已存在且文件已消失的行；
     * 重建期间新写入块的行（createdAt 晚于开始时刻）一律保留，文件不在快照中也不遗漏。
     */
    public RebuildResponse rebuild() {
        // 重建开始前的索引快照：清理阶段只处理这些存量行，增量写入一律保留
        List<String> preExistingChunkIds = index.findAllChunkIds();
        List<ChunkFileStore.ScanEntry> entries;
        try {
            entries = store.scanAll();
        } catch (IOException e) {
            throw new IllegalStateException("块目录扫描失败", e);
        }

        int indexed = 0;
        int bad = 0;
        List<String> quarantinedFiles = new ArrayList<>();
        List<String> goodFiles = new ArrayList<>();

        for (ChunkFileStore.ScanEntry entry : entries) {
            if (entry instanceof ChunkFileStore.ScanOk ok) {
                goodFiles.add(ok.fileName());
                indexed += index.insertIfAbsent(ok.metadata());
            } else if (entry instanceof ChunkFileStore.ScanBad badEntry) {
                bad++;
                quarantinedFiles.add(badEntry.fileName());
                try {
                    Path moved = store.quarantine(badEntry.fileName());
                    audit.record(EVENT_REBUILD_BAD, guessChunkId(badEntry.fileName()),
                            moved.getFileName().toString(), badEntry.reason(), Instant.now(clock));
                } catch (IOException e) {
                    log.error("损坏文件隔离失败: {}", badEntry.fileName(), e);
                }
            }
        }

        int staleDeleted = index.deleteStaleAmong(preExistingChunkIds, goodFiles);
        long total = index.count();
        return new RebuildResponse(entries.size(), indexed, bad, staleDeleted,
                total, List.copyOf(quarantinedFiles));
    }

    private void quarantine(ChunkMetadata md, String reason) {
        try {
            Path moved = store.quarantine(md.fileName());
            index.deleteByChunkId(md.chunkId());
            audit.record(EVENT_QUARANTINED, md.chunkId(),
                    moved.getFileName().toString(), reason, Instant.now(clock));
        } catch (IOException e) {
            log.error("损坏块隔离失败: {}", md.chunkId(), e);
        }
    }

    private static boolean inTimeRange(long t, Long startTs, Long endTs) {
        return (startTs == null || t >= startTs) && (endTs == null || t <= endTs);
    }

    private static String guessChunkId(String fileName) {
        try {
            String stem = fileName.substring(0, fileName.length() - ".lpc".length());
            return java.net.URLDecoder.decode(stem, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record TimeRange(long startTs, long endTs) {
    }

    private static TimeRange timeRange(List<PointDatum> points) {
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (PointDatum p : points) {
            start = Math.min(start, p.t());
            end = Math.max(end, p.t());
        }
        return new TimeRange(start, end);
    }

    private static com.example.starter.lidar.model.BBox boundingBox(List<PointDatum> points) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (PointDatum p : points) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }
        return new com.example.starter.lidar.model.BBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
