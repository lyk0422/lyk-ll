package com.example.starter.costmap.service;

import com.example.starter.costmap.domain.CellRegion;
import com.example.starter.costmap.domain.LiveGridView;
import com.example.starter.costmap.domain.ObstacleStatement;
import com.example.starter.costmap.domain.StatementKind;
import com.example.starter.costmap.persistence.CostmapPo;
import com.example.starter.costmap.persistence.CostmapRepository;
import com.example.starter.costmap.persistence.SnapshotPo;
import com.example.starter.costmap.time.TimeService;
import com.example.starter.costmap.web.ApiException;
import com.example.starter.costmap.web.ErrorCode;
import com.example.starter.costmap.web.dto.CellDto;
import com.example.starter.costmap.web.dto.CellRegionResponse;
import com.example.starter.costmap.web.dto.CreateMapRequest;
import com.example.starter.costmap.web.dto.DiffEntry;
import com.example.starter.costmap.web.dto.MapResponse;
import com.example.starter.costmap.web.dto.ObstacleRequest;
import com.example.starter.costmap.web.dto.ObstacleResponse;
import com.example.starter.costmap.web.dto.PurgeResponse;
import com.example.starter.costmap.web.dto.RegionDiffResponse;
import com.example.starter.costmap.web.dto.RegionDto;
import com.example.starter.costmap.web.dto.RestoreResponse;
import com.example.starter.costmap.web.dto.SnapshotRequest;
import com.example.starter.costmap.web.dto.SnapshotResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 动态代价地图应用服务实现。
 *
 * <p>所有写操作在单地图行锁（SELECT ... FOR UPDATE）内执行，串行化同一地图的并发更新；
 * 栅格修改一律走 {@link LiveGridView} 的区域增量重算，不重建整图。</p>
 */
@Service
public class CostmapServiceImpl implements CostmapService {

    private final CostmapRepository repository;
    private final TimeService timeService;

    public CostmapServiceImpl(CostmapRepository repository, TimeService timeService) {
        this.repository = repository;
        this.timeService = timeService;
    }

    @Override
    @Transactional
    public MapResponse createMap(CreateMapRequest request) {
        int length = request.width() * request.height();
        int[] staticCells = request.staticCells();
        int[] cells = new int[length];
        if (staticCells != null) {
            if (staticCells.length != length) {
                throw new ApiException(ErrorCode.BAD_REQUEST,
                        "staticCells length " + staticCells.length
                                + " does not match width*height " + length);
            }
            for (int i = 0; i < staticCells.length; i++) {
                int v = staticCells[i];
                if (v < 0 || v > 254) {
                    throw new ApiException(ErrorCode.BAD_REQUEST,
                            "static cell at index " + i + " has illegal cost " + v);
                }
                cells[i] = v;
            }
        }
        if (request.inscribedRadius() > request.inflationRadius()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "inscribedRadius must not exceed inflationRadius");
        }

        long now = timeService.nowMillis();
        String mapId = request.mapId() == null || request.mapId().isBlank()
                ? "map-" + UUID.randomUUID()
                : request.mapId();

        LiveGridView view = LiveGridView.initial(request.width(), request.height(),
                request.inscribedRadius(), request.inflationRadius(),
                request.costScalingFactor(), cells);

        CostmapPo po = new CostmapPo(mapId, request.width(), request.height(),
                request.resolution(), request.originX(), request.originY(),
                request.inscribedRadius(), request.inflationRadius(),
                request.costScalingFactor(),
                view.staticCells(), view.liveCells(), view.winnerCells(),
                0L, 0L, 0L, now);
        try {
            repository.insertMap(po);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "map version id already exists: " + mapId);
        }
        return toMapResponse(po);
    }

    @Override
    public MapResponse getMap(String mapId) {
        return toMapResponse(requireMap(mapId));
    }

    @Override
    public CellRegionResponse queryRegion(String mapId, RegionDto region) {
        CostmapPo map = requireMap(mapId);
        CellRegion r = toRegion(region).validateWithin(map.width(), map.height());
        return readRegion(mapId, r, map.liveCells(), map.width(), map.liveSeq());
    }

    @Override
    @Transactional
    public ObstacleResponse applyObstacle(String mapId, ObstacleRequest request) {
        CostmapPo map = repository.lockMap(mapId)
                .orElseThrow(() -> new ApiException(ErrorCode.MAP_NOT_FOUND,
                        "map version not found: " + mapId));
        long now = timeService.nowMillis();

        StatementKind kind = parseKind(request.kind());
        CellRegion region = toRegion(request.region()).validateWithin(map.width(), map.height());
        long eventTime = request.eventTime() == null ? now : request.eventTime();
        long expireAt = eventTime + request.ttlMillis();

        String fingerprint = Fingerprints.of(request.sourceId(), request.obstacleId(),
                request.priority(), kind.name(), region.x0(), region.y0(), region.x1(), region.y1(),
                request.eventTime(), request.ttlMillis());

        var existing = repository.findRequest(mapId, request.requestId());
        if (existing.isPresent()) {
            if (!existing.get().fingerprint().equals(fingerprint)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "requestId " + request.requestId()
                                + " was already applied with different parameters");
            }
            return new ObstacleResponse(true, existing.get().appliedSeq(), map.liveSeq(),
                    null, List.of());
        }

        long seq;
        ObstacleStatement statement;
        try {
            seq = repository.nextStatementSeq(mapId);
            statement = new ObstacleStatement(seq, request.requestId(),
                    request.sourceId(), request.obstacleId(), request.priority(), kind, region,
                    eventTime, request.ttlMillis(), expireAt, now);
            repository.insertStatement(mapId, statement, fingerprint);
            repository.insertRequest(mapId, request.requestId(), fingerprint, seq, now);
        } catch (DuplicateKeyException e) {
            // 并发兜底：行锁下不应到达，此处保持稳定语义。
            var raced = repository.findRequest(mapId, request.requestId());
            if (raced.isPresent()) {
                if (raced.get().fingerprint().equals(fingerprint)) {
                    return new ObstacleResponse(true, raced.get().appliedSeq(), map.liveSeq(),
                            null, List.of());
                }
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "requestId " + request.requestId()
                                + " was already applied with different parameters");
            }
            throw new ApiException(ErrorCode.CONCURRENT_UPDATE, "concurrent update conflict");
        }

        List<ObstacleStatement> active = repository.findActiveStatements(mapId, now);
        LiveGridView view = toView(map);
        // 重算区域以刚应用的声明为准（其可能 TTL=0 已立即过期，不在 active 列表内）。
        LiveGridView.RecomputeResult result = view.applyStatement(statement, active, now);

        long newLiveSeq = map.liveSeq() + (result.viewChanged() ? 1L : 0L);
        repository.updateLiveView(mapId, view.liveCells(), view.winnerCells(), newLiveSeq);

        return new ObstacleResponse(false, seq, newLiveSeq,
                toDto(result.region()), changedCells(result));
    }

    @Override
    @Transactional
    public PurgeResponse purgeExpired(String mapId) {
        CostmapPo map = repository.lockMap(mapId)
                .orElseThrow(() -> new ApiException(ErrorCode.MAP_NOT_FOUND,
                        "map version not found: " + mapId));
        long now = timeService.nowMillis();

        List<ObstacleStatement> expired = repository.findExpiredStatements(mapId, now);
        if (expired.isEmpty()) {
            return new PurgeResponse(0, List.of(), map.liveSeq());
        }

        List<ObstacleStatement> remaining = repository.findActiveStatements(mapId, now);
        List<CellRegion> removedRegions = new ArrayList<>();
        for (ObstacleStatement s : expired) {
            removedRegions.add(s.region());
        }

        LiveGridView view = toView(map);
        LiveGridView.RecomputeResult result = view.afterRemoval(removedRegions, remaining, now);

        // 删除语句带 expire_at <= now 条件：同障碍物更新更晚（过期时刻更晚）的版本不会被误删。
        int removed = repository.deleteExpiredStatements(mapId, now);
        long newLiveSeq = result.viewChanged() ? map.liveSeq() + 1L : map.liveSeq();
        repository.updateLiveView(mapId, view.liveCells(), view.winnerCells(), newLiveSeq);

        return new PurgeResponse(removed, mergeRegions(removedRegions), newLiveSeq);
    }

    @Override
    @Transactional
    public SnapshotResponse publishSnapshot(String mapId, SnapshotRequest request) {
        CostmapPo map = repository.lockMap(mapId)
                .orElseThrow(() -> new ApiException(ErrorCode.MAP_NOT_FOUND,
                        "map version not found: " + mapId));
        long now = timeService.nowMillis();
        String snapshotId = request == null
                || request.snapshotId() == null || request.snapshotId().isBlank()
                ? "snapshot-" + UUID.randomUUID()
                : request.snapshotId();
        long snapshotSeq = repository.nextSnapshotSeq(mapId);

        SnapshotPo po = new SnapshotPo(mapId, snapshotId, snapshotSeq,
                request == null ? null : request.label(),
                map.width(), map.height(), map.liveSeq(), map.allocSeq(),
                map.liveCells().clone(), map.winnerCells().clone(), now);
        try {
            repository.insertSnapshot(po);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "snapshot id already exists: " + snapshotId);
        }
        List<ObstacleStatement> active = repository.findActiveStatements(mapId, now);
        repository.insertSnapshotStatements(mapId, snapshotId, active);
        return new SnapshotResponse(mapId, snapshotId, snapshotSeq, po.label(),
                map.width(), map.height(), map.liveSeq(), active.size(), now);
    }

    @Override
    public SnapshotResponse getSnapshot(String mapId, String snapshotId) {
        SnapshotPo snapshot = requireSnapshot(mapId, snapshotId);
        long count = repository.countSnapshotStatements(mapId, snapshotId);
        return new SnapshotResponse(mapId, snapshotId, snapshot.seq(), snapshot.label(),
                snapshot.width(), snapshot.height(), snapshot.liveSeq(), count,
                snapshot.createdAt());
    }

    @Override
    public CellRegionResponse querySnapshotRegion(String mapId, String snapshotId, RegionDto region) {
        CostmapPo map = requireMap(mapId);
        SnapshotPo snapshot = requireSnapshot(mapId, snapshotId);
        CellRegion r = toRegion(region).validateWithin(map.width(), map.height());
        return readRegion(mapId, r, snapshot.cellData(), map.width(), snapshot.liveSeq());
    }

    @Override
    public RegionDiffResponse diffRegion(String mapId, String snapshotId, RegionDto region) {
        CostmapPo map = requireMap(mapId);
        SnapshotPo snapshot = requireSnapshot(mapId, snapshotId);
        CellRegion r = toRegion(region).validateWithin(map.width(), map.height());
        List<DiffEntry> entries = new ArrayList<>();
        int width = map.width();
        for (int y = r.y0(); y <= r.y1(); y++) {
            for (int x = r.x0(); x <= r.x1(); x++) {
                int idx = y * width + x;
                int snapshotCost = snapshot.cellData()[idx];
                int liveCost = map.liveCells()[idx];
                if (snapshotCost != liveCost) {
                    entries.add(new DiffEntry(x, y, snapshotCost, liveCost));
                }
            }
        }
        return new RegionDiffResponse(mapId, snapshotId, toDto(r), snapshot.seq(),
                map.liveSeq(), entries);
    }

    @Override
    @Transactional
    public RestoreResponse restoreSnapshot(String mapId, String snapshotId) {
        CostmapPo map = repository.lockMap(mapId)
                .orElseThrow(() -> new ApiException(ErrorCode.MAP_NOT_FOUND,
                        "map version not found: " + mapId));
        SnapshotPo snapshot = requireSnapshot(mapId, snapshotId);
        List<ObstacleStatement> statements =
                repository.findSnapshotStatements(mapId, snapshotId);
        repository.restoreSnapshot(map, snapshot, statements);
        return new RestoreResponse(mapId, snapshotId, snapshot.liveSeq(),
                Math.max(map.allocSeq(), snapshot.allocSeq()), statements.size());
    }

    // ---- 辅助方法（置于类末尾） ----

    private CostmapPo requireMap(String mapId) {
        return repository.findMap(mapId)
                .orElseThrow(() -> new ApiException(ErrorCode.MAP_NOT_FOUND,
                        "map version not found: " + mapId));
    }

    private SnapshotPo requireSnapshot(String mapId, String snapshotId) {
        requireMap(mapId);
        return repository.findSnapshot(mapId, snapshotId)
                .orElseThrow(() -> new ApiException(ErrorCode.SNAPSHOT_NOT_FOUND,
                        "snapshot not found: " + snapshotId));
    }

    private static StatementKind parseKind(String kind) {
        try {
            return StatementKind.valueOf(kind.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "kind must be OCCUPY or CLEAR");
        }
    }

    private static CellRegion toRegion(RegionDto dto) {
        return new CellRegion(dto.x0(), dto.y0(), dto.x1(), dto.y1());
    }

    private static RegionDto toDto(CellRegion region) {
        return new RegionDto(region.x0(), region.y0(), region.x1(), region.y1());
    }

    private static LiveGridView toView(CostmapPo map) {
        return new LiveGridView(map.width(), map.height(),
                map.inscribedRadius(), map.inflationRadius(), map.costScalingFactor(),
                map.staticCells(), map.liveCells(), map.winnerCells());
    }

    private static CellRegionResponse readRegion(String mapId, CellRegion r,
                                                 int[] cells, int fullWidth, long liveSeq) {
        List<CellDto> out = new ArrayList<>(r.area());
        for (int y = r.y0(); y <= r.y1(); y++) {
            for (int x = r.x0(); x <= r.x1(); x++) {
                out.add(new CellDto(x, y, cells[y * fullWidth + x]));
            }
        }
        return new CellRegionResponse(mapId, toDto(r), liveSeq, out);
    }

    private static List<CellDto> changedCells(LiveGridView.RecomputeResult result) {
        List<CellDto> out = new ArrayList<>(result.changed().size());
        for (int[] c : result.changed()) {
            out.add(new CellDto(c[0], c[1], c[3]));
        }
        return out;
    }

    private static List<RegionDto> mergeRegions(List<CellRegion> regions) {
        List<CellRegion> merged = new ArrayList<>();
        for (CellRegion region : regions) {
            boolean absorbed = false;
            for (int i = 0; i < merged.size(); i++) {
                CellRegion existing = merged.get(i);
                if (region.intersects(existing)) {
                    merged.set(i, existing.union(region));
                    absorbed = true;
                    break;
                }
            }
            if (!absorbed) {
                merged.add(region);
            }
        }
        return merged.stream().map(CostmapServiceImpl::toDto).toList();
    }

    private static MapResponse toMapResponse(CostmapPo po) {
        return new MapResponse(po.id(), po.width(), po.height(), po.resolution(),
                po.originX(), po.originY(), po.inscribedRadius(), po.inflationRadius(),
                po.costScalingFactor(), po.liveSeq(), po.allocSeq(), po.snapshotSeq(),
                po.createdAt());
    }
}
