package com.example.starter.costmap;

import com.example.starter.costmap.dto.CleanupResponse;
import com.example.starter.costmap.dto.CreateMapRequest;
import com.example.starter.costmap.dto.DiffResponse;
import com.example.starter.costmap.dto.MapView;
import com.example.starter.costmap.dto.ObstacleUpdateRequest;
import com.example.starter.costmap.dto.ObstacleUpdateResponse;
import com.example.starter.costmap.dto.SnapshotView;
import com.example.starter.costmap.error.CostmapException;
import com.example.starter.costmap.error.ErrorCode;
import com.example.starter.costmap.model.CostmapState;
import com.example.starter.costmap.model.ObstacleRecord;
import com.example.starter.costmap.model.Region;
import com.example.starter.costmap.repo.ObstacleRequestRepository;
import com.example.starter.costmap.repo.ObstacleUpdateRepository;
import com.example.starter.costmap.repo.SnapshotRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 代价地图业务服务：地图生命周期、障碍物增量更新、版本快照与差异查询。
 * <p>
 * 每张地图的变更在地图级锁内串行执行，合并结果只取决于公开决胜规则
 * （优先级、时间戳、来源 ID），与请求到达顺序无关。
 * 幂等记录、障碍物审计与版本快照持久化在 H2 内存库中，仅随 JVM 生命周期保留。
 */
@Service
public class CostmapService {

    private final ConcurrentMap<String, CostmapState> maps = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    private final SnapshotRepository snapshots;
    private final ObstacleRequestRepository requests;
    private final ObstacleUpdateRepository updates;
    private final Clock clock;

    public CostmapService(SnapshotRepository snapshots,
                          ObstacleRequestRepository requests,
                          ObstacleUpdateRepository updates,
                          Clock clock) {
        this.snapshots = snapshots;
        this.requests = requests;
        this.updates = updates;
        this.clock = clock;
    }

    /**
     * 创建地图（版本 0 为静态层叠加膨胀后的初始视图）。
     */
    public MapView createMap(CreateMapRequest request) {
        if (request == null) {
            throw new CostmapException(ErrorCode.INVALID_REQUEST, "request body is required");
        }
        Integer width = request.width();
        Integer height = request.height();
        if (width == null || height == null || width < 1 || height < 1) {
            throw new CostmapException(ErrorCode.INVALID_REQUEST, "width and height must be positive integers");
        }
        Double resolution = request.resolution();
        if (resolution == null || resolution <= 0) {
            throw new CostmapException(ErrorCode.INVALID_REQUEST, "resolution must be positive");
        }
        Double inflationRadius = request.inflationRadius();
        if (inflationRadius == null || inflationRadius < 0) {
            throw new CostmapException(ErrorCode.INVALID_REQUEST, "inflationRadius must be non-negative");
        }
        byte[] staticGrid;
        if (request.staticGrid() == null) {
            staticGrid = new byte[width * height];
        } else {
            if (request.staticGrid().length != width * height) {
                throw new CostmapException(ErrorCode.INVALID_REQUEST,
                        "staticGrid length must equal width*height");
            }
            staticGrid = toBytes(request.staticGrid());
        }
        String mapId = request.mapId() == null || request.mapId().isBlank()
                ? UUID.randomUUID().toString() : request.mapId();
        CostmapState state = new CostmapState(mapId, width, height, resolution, inflationRadius, staticGrid);
        synchronized (lock(mapId)) {
            if (maps.putIfAbsent(mapId, state) != null) {
                throw new CostmapException(ErrorCode.REQUEST_CONFLICT, "map already exists: " + mapId);
            }
            CostmapEngine.recomputeRegion(state, fullRegion(state));
            commitVersion(state);
            return toView(state, state.masterGrid());
        }
    }

    /**
     * 读取地图当前版本视图。
     */
    public MapView currentView(String mapId) {
        synchronized (lock(mapId)) {
            CostmapState state = requireMap(mapId);
            return toView(state, state.masterGrid());
        }
    }

    /**
     * 读取指定版本视图；版本不存在返回 404。
     */
    public MapView versionView(String mapId, long version) {
        synchronized (lock(mapId)) {
            CostmapState state = requireMap(mapId);
            byte[] grid = state.versions().get(version);
            if (grid == null) {
                throw new CostmapException(ErrorCode.VERSION_NOT_FOUND,
                        "version not found: " + version);
            }
            return new MapView(state.mapId(), version, state.width(), state.height(),
                    state.resolution(), state.inflationRadius(), toInts(grid));
        }
    }

    /**
     * 应用障碍物更新（幂等）；仅重算受影响区域及其膨胀边界。
     */
    public ObstacleUpdateResponse applyObstacle(String mapId, ObstacleUpdateRequest request) {
        validateObstacleRequest(request);
        synchronized (lock(mapId)) {
            CostmapState state = requireMap(mapId);
            validateRegionAgainstMap(state, request.region());
            String hash = hashRequest(request);
            var existing = requests.find(mapId, request.requestId());
            if (existing.isPresent()) {
                ObstacleRequestRepository.Record record = existing.get();
                if (!record.requestHash().equals(hash)) {
                    throw new CostmapException(ErrorCode.REQUEST_CONFLICT,
                            "requestId already used with different parameters: " + request.requestId());
                }
                Region affected = request.region().expand(
                        CostmapEngine.inflationCells(state.inflationRadius(), state.resolution()),
                        state.width(), state.height());
                return new ObstacleUpdateResponse(mapId, request.requestId(),
                        record.appliedVersion(), affected, true);
            }
            cleanupExpiredLocked(state);
            if (request.obstacleKey() != null) {
                state.obstacles().removeIf(o -> o.sourceId().equals(request.sourceId())
                        && request.obstacleKey().equals(o.obstacleKey()));
            }
            int priority = request.priority() == null ? 0 : request.priority();
            Long expiresAt = request.ttlMillis() == null ? null : clock.millis() + request.ttlMillis();
            ObstacleRecord obstacle = new ObstacleRecord(
                    UUID.randomUUID().toString(),
                    request.requestId(),
                    request.sourceId(),
                    priority,
                    request.obstacleKey(),
                    request.region(),
                    toBytes(request.cells()),
                    request.timestamp(),
                    expiresAt);
            state.obstacles().add(obstacle);
            Region affected = request.region().expand(
                    CostmapEngine.inflationCells(state.inflationRadius(), state.resolution()),
                    state.width(), state.height());
            Region recomputed = CostmapEngine.recomputeRegion(state, affected);
            long version = commitVersion(state);
            Instant now = clock.instant();
            requests.insert(mapId, request.requestId(), hash, version, now);
            updates.insert(mapId, obstacle, version, now);
            return new ObstacleUpdateResponse(mapId, request.requestId(), version, recomputed, false);
        }
    }

    /**
     * 发布指定版本（缺省当前版本）的不可变快照到 H2。
     */
    public SnapshotView publishSnapshot(String mapId, Long version) {
        synchronized (lock(mapId)) {
            CostmapState state = requireMap(mapId);
            long target = version == null ? state.currentVersion() : version;
            byte[] grid = state.versions().get(target);
            if (grid == null) {
                throw new CostmapException(ErrorCode.VERSION_NOT_FOUND,
                        "version not found: " + target);
            }
            Instant now = clock.instant();
            try {
                snapshots.insert(mapId, target, state.width(), state.height(),
                        state.resolution(), state.inflationRadius(), grid.clone(), now);
            } catch (DuplicateKeyException e) {
                throw new CostmapException(ErrorCode.SNAPSHOT_CONFLICT,
                        "snapshot already published: mapId=" + mapId + ", version=" + target);
            }
            return new SnapshotView(mapId, target, state.width(), state.height(),
                    state.resolution(), state.inflationRadius(), toInts(grid), now.toString());
        }
    }

    /**
     * 从 H2 读取快照。
     */
    public SnapshotView snapshot(String mapId, long version) {
        return snapshots.find(mapId, version)
                .orElseThrow(() -> new CostmapException(ErrorCode.SNAPSHOT_NOT_FOUND,
                        "snapshot not found: mapId=" + mapId + ", version=" + version));
    }

    /**
     * 用 H2 中的快照恢复查询视图（生成新版本，不影响静态层与障碍物状态）。
     */
    public MapView restoreSnapshot(String mapId, long version) {
        synchronized (lock(mapId)) {
            CostmapState state = requireMap(mapId);
            SnapshotView snapshot = snapshots.find(mapId, version)
                    .orElseThrow(() -> new CostmapException(ErrorCode.SNAPSHOT_NOT_FOUND,
                            "snapshot not found: mapId=" + mapId + ", version=" + version));
            if (snapshot.width() != state.width() || snapshot.height() != state.height()) {
                throw new CostmapException(ErrorCode.INVALID_REQUEST,
                        "snapshot dimensions do not match map");
            }
            byte[] grid = toBytes(snapshot.grid());
            System.arraycopy(grid, 0, state.masterGrid(), 0, grid.length);
            commitVersion(state);
            return toView(state, state.masterGrid());
        }
    }

    /**
     * 查询两个版本在指定区域内的差异；区域为空时按全图处理。
     */
    public DiffResponse diff(String mapId, long fromVersion, long toVersion, Region region) {
        synchronized (lock(mapId)) {
            CostmapState state = requireMap(mapId);
            byte[] from = state.versions().get(fromVersion);
            if (from == null) {
                throw new CostmapException(ErrorCode.VERSION_NOT_FOUND,
                        "version not found: " + fromVersion);
            }
            byte[] to = state.versions().get(toVersion);
            if (to == null) {
                throw new CostmapException(ErrorCode.VERSION_NOT_FOUND,
                        "version not found: " + toVersion);
            }
            Region target = region == null ? fullRegion(state) : region;
            if (!target.fitsWithin(state.width(), state.height())) {
                throw new CostmapException(ErrorCode.INVALID_REGION,
                        "region out of map bounds: " + target);
            }
            List<DiffResponse.CellChange> changes = new ArrayList<>();
            for (int y = target.y(); y < target.y() + target.height(); y++) {
                for (int x = target.x(); x < target.x() + target.width(); x++) {
                    int idx = y * state.width() + x;
                    int fromCost = from[idx] & 0xFF;
                    int toCost = to[idx] & 0xFF;
                    if (fromCost != toCost) {
                        changes.add(new DiffResponse.CellChange(x, y, fromCost, toCost));
                    }
                }
            }
            return new DiffResponse(mapId, fromVersion, toVersion, target, changes);
        }
    }

    /**
     * 清理过期障碍物（只移除过期时刻不晚于当前时间的记录），并重算受影响区域。
     */
    public CleanupResponse cleanup(String mapId) {
        synchronized (lock(mapId)) {
            CostmapState state = requireMap(mapId);
            int removed = cleanupExpiredLocked(state);
            return new CleanupResponse(mapId, removed, state.currentVersion());
        }
    }

    /**
     * 移除过期记录并按并集区域增量重算；仅在确实移除记录时推进版本。
     * 过期判断在清理当下重新进行，晚于清理时刻更新的记录（过期时刻更晚或为空）不会被误删。
     */
    private int cleanupExpiredLocked(CostmapState state) {
        long now = clock.millis();
        List<ObstacleRecord> expired = state.obstacles().stream()
                .filter(o -> o.expiresAt() != null && o.expiresAt() <= now)
                .toList();
        if (expired.isEmpty()) {
            return 0;
        }
        state.obstacles().removeAll(expired);
        int radius = CostmapEngine.inflationCells(state.inflationRadius(), state.resolution());
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int y1 = Integer.MIN_VALUE;
        for (ObstacleRecord obstacle : expired) {
            Region r = obstacle.region();
            x0 = Math.min(x0, r.x());
            y0 = Math.min(y0, r.y());
            x1 = Math.max(x1, r.x() + r.width());
            y1 = Math.max(y1, r.y() + r.height());
        }
        Region union = new Region(x0, y0, x1 - x0, y1 - y0)
                .expand(radius, state.width(), state.height());
        CostmapEngine.recomputeRegion(state, union);
        commitVersion(state);
        return expired.size();
    }

    private void validateObstacleRequest(ObstacleUpdateRequest request) {
        if (request == null) {
            throw new CostmapException(ErrorCode.INVALID_REQUEST, "request body is required");
        }
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw new CostmapException(ErrorCode.INVALID_REQUEST, "requestId is required");
        }
        if (request.sourceId() == null || request.sourceId().isBlank()) {
            throw new CostmapException(ErrorCode.INVALID_REQUEST, "sourceId is required");
        }
        if (request.region() == null) {
            throw new CostmapException(ErrorCode.INVALID_REGION, "region is required");
        }
        if (request.timestamp() == null) {
            throw new CostmapException(ErrorCode.INVALID_REQUEST, "timestamp is required");
        }
        if (request.ttlMillis() != null && request.ttlMillis() <= 0) {
            throw new CostmapException(ErrorCode.INVALID_REQUEST, "ttlMillis must be positive");
        }
        if (request.cells() == null
                || request.cells().length != (long) request.region().width() * request.region().height()) {
            throw new CostmapException(ErrorCode.INVALID_REQUEST,
                    "cells length must equal region.width*region.height");
        }
        for (int cell : request.cells()) {
            if (cell < 0 || cell > 255) {
                throw new CostmapException(ErrorCode.INVALID_REQUEST,
                        "cell cost out of range [0,255]: " + cell);
            }
        }
    }

    private void validateRegionAgainstMap(CostmapState state, Region region) {
        if (!region.fitsWithin(state.width(), state.height())) {
            throw new CostmapException(ErrorCode.INVALID_REGION,
                    "region out of map bounds: " + region);
        }
    }

    private CostmapState requireMap(String mapId) {
        CostmapState state = maps.get(mapId);
        if (state == null) {
            throw new CostmapException(ErrorCode.MAP_NOT_FOUND, "map not found: " + mapId);
        }
        return state;
    }

    private Object lock(String mapId) {
        return locks.computeIfAbsent(mapId, k -> new Object());
    }

    private long commitVersion(CostmapState state) {
        long version = state.currentVersion() + 1;
        state.currentVersion(version);
        state.versions().put(version, state.masterGrid().clone());
        return version;
    }

    private MapView toView(CostmapState state, byte[] grid) {
        return new MapView(state.mapId(), state.currentVersion(), state.width(), state.height(),
                state.resolution(), state.inflationRadius(), toInts(grid));
    }

    private Region fullRegion(CostmapState state) {
        return new Region(0, 0, state.width(), state.height());
    }

    private String hashRequest(ObstacleUpdateRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.sourceId()).append('|')
                .append(request.priority() == null ? 0 : request.priority()).append('|')
                .append(request.obstacleKey() == null ? "" : request.obstacleKey()).append('|')
                .append(request.region().x()).append(',')
                .append(request.region().y()).append(',')
                .append(request.region().width()).append(',')
                .append(request.region().height()).append('|')
                .append(request.timestamp()).append('|')
                .append(request.ttlMillis() == null ? "" : request.ttlMillis()).append('|');
        for (int cell : request.cells()) {
            sb.append(cell).append(',');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] toBytes(int[] values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            int v = values[i];
            if (v < 0 || v > 255) {
                throw new CostmapException(ErrorCode.INVALID_REQUEST,
                        "cell cost out of range [0,255]: " + v);
            }
            out[i] = (byte) v;
        }
        return out;
    }

    private static int[] toInts(byte[] values) {
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] & 0xFF;
        }
        return out;
    }
}
