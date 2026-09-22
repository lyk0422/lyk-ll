package com.example.starter.costmap.service;

import com.example.starter.costmap.model.GridRegion;
import com.example.starter.costmap.model.MapVersion;
import com.example.starter.costmap.model.ObstacleEvent;
import com.example.starter.costmap.repo.DynamicCostRepository;
import com.example.starter.costmap.repo.MapVersionRepository;
import com.example.starter.costmap.repo.ObstacleRepository;
import com.example.starter.costmap.repo.SnapshotRepository;
import com.example.starter.costmap.support.CostMath;
import com.example.starter.costmap.support.DistanceTransform;
import com.example.starter.costmap.support.TimeSource;
import com.example.starter.costmap.view.CostView;
import com.example.starter.costmap.view.LiveView;
import com.example.starter.costmap.view.SnapshotView;
import com.example.starter.costmap.view.ViewRegistry;
import com.example.starter.costmap.web.ApiException;
import com.example.starter.costmap.web.dto.CellChange;
import com.example.starter.costmap.web.dto.CleanupResponse;
import com.example.starter.costmap.web.dto.CreateMapRequest;
import com.example.starter.costmap.web.dto.MapResponse;
import com.example.starter.costmap.web.dto.ObstacleUpdateRequest;
import com.example.starter.costmap.web.dto.RegionCostResponse;
import com.example.starter.costmap.web.dto.RestoreViewResponse;
import com.example.starter.costmap.web.dto.SnapshotResponse;
import com.example.starter.costmap.web.dto.UpdateResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 动态代价地图核心服务：不可变版本、行级合并的障碍物更新、仅重算受影响区域及其膨胀边界、
 * requestId 幂等、TTL 安全清理、不可变快照、区域差异与同 JVM 快照视图恢复。
 */
@Service
public class CostmapService {

    /** 差异/查询中表示实时视图的保留引用名。 */
    public static final String LIVE_REF = "live";

    private final MapVersionRepository mapVersionRepository;
    private final ObstacleRepository obstacleRepository;
    private final DynamicCostRepository dynamicCostRepository;
    private final SnapshotRepository snapshotRepository;
    private final CostComputer costComputer;
    private final ViewRegistry viewRegistry;
    private final UpdateClaimTx updateClaimTx;
    private final TimeSource timeSource;

    public CostmapService(MapVersionRepository mapVersionRepository,
                          ObstacleRepository obstacleRepository,
                          DynamicCostRepository dynamicCostRepository,
                          SnapshotRepository snapshotRepository,
                          CostComputer costComputer,
                          ViewRegistry viewRegistry,
                          UpdateClaimTx updateClaimTx,
                          TimeSource timeSource) {
        this.mapVersionRepository = mapVersionRepository;
        this.obstacleRepository = obstacleRepository;
        this.dynamicCostRepository = dynamicCostRepository;
        this.snapshotRepository = snapshotRepository;
        this.costComputer = costComputer;
        this.viewRegistry = viewRegistry;
        this.updateClaimTx = updateClaimTx;
        this.timeSource = timeSource;
    }

    // ---------- 地图版本 ----------

    /** 创建不可变地图版本；创建时一次性预计算静态层膨胀，后续障碍物更新不再重建整图。 */
    public MapResponse createMap(CreateMapRequest request) {
        int width = request.width();
        int height = request.height();
        byte[] raw = toStaticGrid(request.staticGrid(), width, height);

        boolean[] blocked = new boolean[width * height];
        for (int i = 0; i < raw.length; i++) {
            blocked[i] = (raw[i] & 0xFF) == CostMath.LETHAL;
        }
        double[] distances = DistanceTransform.euclideanDistances(blocked, width, height);
        byte[] inflated = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            int rawCost = raw[i] & 0xFF;
            int inflCost = CostMath.inflationCost(distances[i], request.resolution(),
                    request.inflationRadius(), request.costDecay());
            inflated[i] = (byte) Math.max(rawCost, inflCost);
        }

        String versionId = request.versionId() == null || request.versionId().isBlank()
                ? "map-" + UUID.randomUUID()
                : request.versionId();
        MapVersion version = new MapVersion(versionId, width, height, request.resolution(),
                request.inflationRadius(), request.costDecay(), raw, inflated, 0);
        try {
            mapVersionRepository.insert(version);
        } catch (DuplicateKeyException e) {
            throw ApiException.mapExists(versionId);
        }
        return toMapResponse(version);
    }

    // ---------- 障碍物更新 ----------

    /**
     * 应用障碍物更新：行级写入，仅重算区域外扩膨胀半径的范围。
     * 同一 requestId 同参重放不重复应用；异参返回 409。
     */
    @Transactional
    public UpdateResponse updateObstacle(String versionId, ObstacleUpdateRequest request) {
        // 行锁串行化同一版本上的并发更新，保证合并顺序与 seq 分配确定。
        MapVersion version = mapVersionRepository.findByIdForUpdate(versionId)
                .orElseThrow(() -> ApiException.mapNotFound(versionId));

        GridRegion region = validRegion(request.xMin(), request.yMin(),
                request.xMax(), request.yMax(), version);
        int priority = request.priority() == null ? 0 : request.priority();
        long timestampMs = request.timestampMs() == null ? timeSource.nowMs() : request.timestampMs();
        ObstacleEvent event = new ObstacleEvent(request.requestId(), request.sourceId(), priority,
                region, timestampMs, request.ttlMs());
        String fingerprint = fingerprint(versionId, event);

        var existing = obstacleRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint, event);
        }

        if (request.expectedSeq() != null && request.expectedSeq() != version.seq()) {
            throw ApiException.versionConflict(request.expectedSeq(), version.seq());
        }

        long newSeq;
        try {
            newSeq = updateClaimTx.claim(versionId, version.seq(), event, fingerprint);
            obstacleRepository.insertCells(versionId, event);
        } catch (UpdateClaimTx.ClaimDuplicatedException e) {
            // 与并发同 requestId 请求撞唯一键：重新读取走幂等/冲突判定。
            var raced = obstacleRepository.findByRequestId(request.requestId()).orElseThrow();
            return replayOrConflict(raced, fingerprint(versionId, event), event);
        }

        // 仅重算受影响区域及其膨胀边界，绝不重建整图。
        int radius = CostMath.radiusCells(version.inflationRadius(), version.resolution());
        GridRegion recomputeBox = region.dilate(radius, version.width(), version.height());
        costComputer.recomputeBox(version, recomputeBox, newSeq);

        return new UpdateResponse(request.requestId(), versionId, false, newSeq,
                recomputeBox.xMin(), recomputeBox.yMin(), recomputeBox.xMax(), recomputeBox.yMax(),
                recomputeBox.area());
    }

    private UpdateResponse replayOrConflict(ObstacleRepository.StoredUpdate stored,
                                            String fingerprint, ObstacleEvent event) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw ApiException.idempotencyConflict(event.requestId());
        }
        // 同参重放：不重复应用，不增加 seq，不触发重算。
        return new UpdateResponse(stored.requestId(), stored.versionId(), true, stored.seq(),
                stored.region().xMin(), stored.region().yMin(),
                stored.region().xMax(), stored.region().yMax(), 0);
    }

    // ---------- 查询 ----------

    /** 查询实时视图的区域代价。 */
    public RegionCostResponse queryRegion(String versionId, GridRegion region) {
        MapVersion version = getMap(versionId);
        GridRegion valid = validRegion(region.xMin(), region.yMin(), region.xMax(), region.yMax(), version);
        LiveView view = buildLiveView(version, valid);
        Map<Integer, String> owners = costComputer.ownersInRegion(version, valid);
        return liveResponse(versionId, valid, view, owners, null, version.width());
    }

    /** 按引用查询：{@code live} 为实时视图，其它视为快照ID。 */
    public RegionCostResponse queryRef(String versionId, String ref, GridRegion region) {
        MapVersion version = getMap(versionId);
        GridRegion valid = validRegion(region.xMin(), region.yMin(), region.xMax(), region.yMax(), version);
        if (LIVE_REF.equalsIgnoreCase(ref)) {
            LiveView view = buildLiveView(version, valid);
            Map<Integer, String> owners = costComputer.ownersInRegion(version, valid);
            return liveResponse(versionId, valid, view, owners, null, version.width());
        }
        return snapshotResponse(buildSnapshotView(version, ref, valid), valid, null);
    }

    /** 从 H2 保存的快照恢复同 JVM 内查询视图。 */
    public RestoreViewResponse restoreView(String snapshotId) {
        var meta = snapshotRepository.findMeta(snapshotId)
                .orElseThrow(() -> ApiException.snapshotNotFound(snapshotId));
        String viewId = viewRegistry.register(snapshotId);
        return new RestoreViewResponse(viewId, snapshotId, meta.versionId(), meta.seq());
    }

    /** 查询恢复视图的区域代价；视图数据始终来自不可变快照表。 */
    public RegionCostResponse queryViewById(String viewId, GridRegion region) {
        String snapshotId = viewRegistry.snapshotOf(viewId)
                .orElseThrow(() -> ApiException.viewNotFound(viewId));
        var meta = snapshotRepository.findMeta(snapshotId)
                .orElseThrow(() -> ApiException.snapshotNotFound(snapshotId));
        MapVersion version = getMap(meta.versionId());
        GridRegion valid = validRegion(region.xMin(), region.yMin(), region.xMax(), region.yMax(), version);
        return snapshotResponse(buildSnapshotView(version, snapshotId, valid), valid, viewId);
    }

    // ---------- 快照与差异 ----------

    /** 发布不可变快照：冻结当前实时代价，之后更新与 TTL 清理均不影响快照。 */
    @Transactional
    public SnapshotResponse publishSnapshot(String versionId, String snapshotId) {
        MapVersion version = mapVersionRepository.findByIdForUpdate(versionId)
                .orElseThrow(() -> ApiException.mapNotFound(versionId));
        String id = snapshotId == null || snapshotId.isBlank()
                ? "snap-" + UUID.randomUUID()
                : snapshotId;
        try {
            snapshotRepository.insertSnapshot(id, versionId, version.seq());
        } catch (DuplicateKeyException e) {
            throw ApiException.snapshotExists(id);
        }
        // 冻结所有曾被动态重算的格点；其余格点恒等于不可变静态膨胀层。
        snapshotRepository.insertCells(id, dynamicCostRepository.findAllCosts(versionId));
        return new SnapshotResponse(id, versionId, version.seq());
    }

    /** 区域差异：逐格比较两个引用（live 或快照ID）在同一区域的代价。 */
    public CellChange.DiffResponse diff(String versionId, String fromRef, String toRef, GridRegion region) {
        MapVersion version = getMap(versionId);
        GridRegion valid = validRegion(region.xMin(), region.yMin(), region.xMax(), region.yMax(), version);
        CostView from = resolveView(version, fromRef, valid);
        CostView to = resolveView(version, toRef, valid);
        List<CellChange> changes = new ArrayList<>();
        for (int y = valid.yMin(); y <= valid.yMax(); y++) {
            for (int x = valid.xMin(); x <= valid.xMax(); x++) {
                int a = from.costAt(x, y);
                int b = to.costAt(x, y);
                if (a != b) {
                    changes.add(new CellChange(x, y, a, b));
                }
            }
        }
        changes.sort(Comparator.comparingInt(CellChange::y).thenComparingInt(CellChange::x));
        return new CellChange.DiffResponse(versionId, fromRef, toRef,
                valid.xMin(), valid.yMin(), valid.xMax(), valid.yMax(), changes.size(), changes);
    }

    // ---------- TTL 清理 ----------

    /**
     * 清理过期障碍物行：只删除 expires_at &lt; at 的行（默认 at 取当前时钟），
     * 同一格点更新更晚（expires_at 更大）的数据保留；随后只重算过期格点外扩膨胀半径的并集。
     */
    @Transactional
    public CleanupResponse cleanup(String versionId, Long atMs) {
        MapVersion version = mapVersionRepository.findByIdForUpdate(versionId)
                .orElseThrow(() -> ApiException.mapNotFound(versionId));
        long now = atMs != null ? atMs : timeSource.nowMs();

        List<int[]> expiredPositions = obstacleRepository.findExpiredCellPositions(versionId, now);
        if (expiredPositions.isEmpty()) {
            return new CleanupResponse(versionId, now, 0, 0);
        }
        int removed = obstacleRepository.deleteExpired(versionId, now);

        int xMin = Integer.MAX_VALUE;
        int yMin = Integer.MAX_VALUE;
        int xMax = Integer.MIN_VALUE;
        int yMax = Integer.MIN_VALUE;
        for (int[] p : expiredPositions) {
            xMin = Math.min(xMin, p[0]);
            yMin = Math.min(yMin, p[1]);
            xMax = Math.max(xMax, p[0]);
            yMax = Math.max(yMax, p[1]);
        }
        int radius = CostMath.radiusCells(version.inflationRadius(), version.resolution());
        GridRegion box = new GridRegion(xMin, yMin, xMax, yMax)
                .dilate(radius, version.width(), version.height());
        costComputer.recomputeBox(version, box, version.seq());
        return new CleanupResponse(versionId, now, removed, box.area());
    }

    // ---------- 内部辅助 ----------

    private MapVersion getMap(String versionId) {
        return mapVersionRepository.findById(versionId)
                .orElseThrow(() -> ApiException.mapNotFound(versionId));
    }

    private CostView resolveView(MapVersion version, String ref, GridRegion region) {
        return LIVE_REF.equalsIgnoreCase(ref)
                ? buildLiveView(version, region)
                : buildSnapshotView(version, ref, region);
    }

    private LiveView buildLiveView(MapVersion version, GridRegion region) {
        Map<Integer, Integer> costs = new HashMap<>();
        for (int[] row : dynamicCostRepository.findCostsInRegion(version.versionId(), region)) {
            costs.put(row[1] * version.width() + row[0], row[2]);
        }
        return new LiveView(version, costs);
    }

    private SnapshotView buildSnapshotView(MapVersion version, String snapshotId, GridRegion region) {
        var meta = snapshotRepository.findMeta(snapshotId)
                .orElseThrow(() -> ApiException.snapshotNotFound(snapshotId));
        if (!meta.versionId().equals(version.versionId())) {
            throw ApiException.invalidRequest(
                    "snapshot " + snapshotId + " does not belong to map " + version.versionId());
        }
        Map<Integer, Integer> frozen = new HashMap<>();
        for (int[] row : snapshotRepository.findCellsInRegion(snapshotId, region)) {
            frozen.put(row[1] * version.width() + row[0], row[2]);
        }
        return new SnapshotView(snapshotId, version, meta.seq(), frozen);
    }

    private RegionCostResponse liveResponse(String versionId, GridRegion region, LiveView view,
                                            Map<Integer, String> owners, String viewId, int width) {
        int cols = region.xMax() - region.xMin() + 1;
        int rows = region.yMax() - region.yMin() + 1;
        int[][] costs = new int[rows][cols];
        String[][] ownerMatrix = new String[rows][cols];
        for (int ry = 0; ry < rows; ry++) {
            for (int rx = 0; rx < cols; rx++) {
                int x = region.xMin() + rx;
                int y = region.yMin() + ry;
                costs[ry][rx] = view.costAt(x, y);
                ownerMatrix[ry][rx] = owners.get(y * width + x);
            }
        }
        return new RegionCostResponse(versionId, null, viewId, view.seq(),
                region.xMin(), region.yMin(), region.xMax(), region.yMax(), costs, ownerMatrix);
    }

    private RegionCostResponse snapshotResponse(SnapshotView view, GridRegion region, String viewId) {
        int cols = region.xMax() - region.xMin() + 1;
        int rows = region.yMax() - region.yMin() + 1;
        int[][] costs = new int[rows][cols];
        for (int ry = 0; ry < rows; ry++) {
            for (int rx = 0; rx < cols; rx++) {
                costs[ry][rx] = view.costAt(region.xMin() + rx, region.yMin() + ry);
            }
        }
        return new RegionCostResponse(view.versionId(), view.snapshotId(), viewId, view.seq(),
                region.xMin(), region.yMin(), region.xMax(), region.yMax(), costs, null);
    }

    private GridRegion validRegion(int xMin, int yMin, int xMax, int yMax, MapVersion version) {
        if (xMin > xMax || yMin > yMax) {
            throw ApiException.invalidRegion("region coordinates must satisfy xMin<=xMax, yMin<=yMax");
        }
        if (xMin < 0 || yMin < 0
                || xMax >= version.width() || yMax >= version.height()) {
            throw ApiException.invalidRegion("region exceeds map bounds "
                    + version.width() + "x" + version.height());
        }
        return new GridRegion(xMin, yMin, xMax, yMax);
    }

    private byte[] toStaticGrid(int[][] grid, int width, int height) {
        byte[] out = new byte[width * height];
        if (grid == null) {
            return out;
        }
        if (grid.length != height) {
            throw ApiException.invalidRequest("staticGrid rows must equal height " + height);
        }
        for (int y = 0; y < height; y++) {
            if (grid[y] == null || grid[y].length != width) {
                throw ApiException.invalidRequest("staticGrid row " + y + " length must equal width " + width);
            }
            for (int x = 0; x < width; x++) {
                int v = grid[y][x];
                if (v < 0 || v > CostMath.LETHAL) {
                    throw ApiException.invalidRequest("staticGrid cost must be in 0..254");
                }
                out[y * width + x] = (byte) v;
            }
        }
        return out;
    }

    private MapResponse toMapResponse(MapVersion v) {
        return new MapResponse(v.versionId(), v.width(), v.height(), v.resolution(),
                v.inflationRadius(), v.costDecay(), v.seq());
    }

    /**
     * 除 requestId 外全部业务参数（含所属版本）的确定性指纹；
     * 同参重放指纹一致，任何参数差异都会导致 409。
     */
    private String fingerprint(String versionId, ObstacleEvent e) {
        String canonical = String.join("|",
                versionId,
                e.sourceId(),
                Integer.toString(e.priority()),
                Integer.toString(e.region().xMin()),
                Integer.toString(e.region().yMin()),
                Integer.toString(e.region().xMax()),
                Integer.toString(e.region().yMax()),
                Long.toString(e.timestampMs()),
                Long.toString(e.ttlMs()));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
