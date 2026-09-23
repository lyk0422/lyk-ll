package com.example.starter.costmap.cost;

import com.example.starter.costmap.ConflictException;
import com.example.starter.costmap.CostmapClock;
import com.example.starter.costmap.InvalidRegionException;
import com.example.starter.costmap.VersionNotFoundException;
import com.example.starter.costmap.cost.LayerMerge.CellKey;
import com.example.starter.costmap.domain.CellCost;
import com.example.starter.costmap.domain.CellView;
import com.example.starter.costmap.domain.MapMeta;
import com.example.starter.costmap.domain.ObstacleInput;
import com.example.starter.costmap.domain.ObstacleRow;
import com.example.starter.costmap.domain.Region;
import com.example.starter.costmap.domain.SnapshotCell;
import com.example.starter.costmap.domain.SourceType;
import com.example.starter.costmap.domain.UpdateResult;
import com.example.starter.costmap.repo.CostmapRepository;
import com.example.starter.costmap.repo.CostmapRepository.IdempotencyRecord;
import com.example.starter.costmap.repo.CostmapRepository.SnapshotRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * {@link CostmapService} 实现。
 *
 * <p>关键语义：</p>
 * <ul>
 *   <li>地图版本不可变，版本号从 1 开始；障碍更新不创建新版本，只在最新版本上叠加动态层；</li>
 *   <li>更新只写入受影响区域的障碍行；膨胀层在查询时按“查询区域 + 膨胀半径”包围盒局部计算，
 *       不在每次更新时重建整图；</li>
 *   <li>多来源合并决胜：来源类型优先级 → eventTime 新者 → sourceId 字典序；</li>
 *   <li>requestId 幂等：同参重放返回首次结果且不重复写入，异参返回 409；</li>
 *   <li>revision 冲突（同一来源并发）由唯一索引兜底并重试；</li>
 *   <li>过期半开区间：{@code expires_at <= now} 视为过期；清理只删已取代旧版本或已过期的
 *       当前版本，晚到期数据不匹配删除条件；</li>
 *   <li>快照写入独立分层单元表，发布后与动态表完全解耦，后续更新/清理不影响快照。</li>
 * </ul>
 */
@Service
public class CostmapServiceImpl implements CostmapService {

    private static final int MAX_REVISION_RETRIES = 10;

    private final CostmapRepository repository;
    private final CostmapClock clock;
    private final TransactionTemplate txTemplate;

    public CostmapServiceImpl(CostmapRepository repository,
                              CostmapClock clock,
                              org.springframework.transaction.PlatformTransactionManager txManager) {
        this.repository = repository;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    @Override
    public MapMeta createMap(CreateMapCommand command) {
        validateCreateCommand(command);
        long now = clock.now();
        MapMeta meta = new MapMeta(
                command.mapId(), 1L, command.width(), command.height(),
                command.resolution(), command.originX(), command.originY(),
                command.inflationRadiusCells(), command.costDecay(), now);
        try {
            return txTemplate.execute(status -> {
                if (repository.existsAnyVersion(meta.mapId())) {
                    throw new ConflictException("MAP_EXISTS",
                            "map already exists: " + meta.mapId());
                }
                repository.insertVersion(meta);
                List<CellCost> nonZero = command.staticCells().stream()
                        .filter(c -> c.cost() != 0)
                        .toList();
                repository.insertStaticCells(meta.mapId(), meta.version(), nonZero, now);
                return meta;
            });
        } catch (DuplicateKeyException e) {
            throw new ConflictException("MAP_EXISTS", "map already exists: " + meta.mapId());
        }
    }

    @Override
    public UpdateResult updateObstacle(String mapId, ObstacleInput input) {
        validateInput(input);
        DuplicateKeyException last = null;
        for (int attempt = 0; attempt < MAX_REVISION_RETRIES; attempt++) {
            try {
                return txTemplate.execute(status -> doUpdate(mapId, input));
            } catch (DuplicateKeyException e) {
                // 唯一索引兜底：幂等键或 (来源, 格, revision) 并发冲突，重试后重新读最新状态
                last = e;
            }
        }
        throw new ConflictException("UPDATE_CONFLICT",
                "concurrent update conflict for request: " + input.requestId(), last);
    }

    private UpdateResult doUpdate(String mapId, ObstacleInput input) {
        long now = clock.now();
        MapMeta meta = requireLatestMap(mapId);
        if (!input.region().fitsIn(meta.width(), meta.height())) {
            throw new InvalidRegionException(
                    "region " + regionText(input.region())
                            + " is outside the map bounds " + meta.width() + "x" + meta.height());
        }

        String fingerprint = fingerprint(input);
        IdempotencyRecord existing = repository.findIdempotency(mapId, input.requestId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new ConflictException("IDEMPOTENCY_CONFLICT",
                        "requestId " + input.requestId()
                                + " was already applied with different parameters");
            }
            // 同参重放必须幂等成功，即使重放时携带的 expectedVersion 已落后
            return new UpdateResult(input.requestId(), true, existing.revision(),
                    input.region().cellCount(), meta.version(), existing.recordedAt());
        }

        if (input.expectedVersion() != null && input.expectedVersion() != meta.version()) {
            throw new ConflictException("VERSION_CONFLICT",
                    "expected version " + input.expectedVersion()
                            + " but current version is " + meta.version());
        }

        long revision = repository.maxRevisionFor(mapId, input.sourceId(), input.region()) + 1;
        Long expiresAt = input.ttlMillis() == null ? null : now + input.ttlMillis();

        // 先落幂等记录：唯一约束保证同一 requestId 只有一个事务能真正应用
        repository.insertIdempotency(mapId, input.requestId(), fingerprint, revision, now);

        List<ObstacleRow> rows = new ArrayList<>(input.region().cellCount());
        for (int y = input.region().y0(); y < input.region().y1(); y++) {
            for (int x = input.region().x0(); x < input.region().x1(); x++) {
                rows.add(new ObstacleRow(
                        mapId, input.requestId(), input.sourceId(), input.sourceType(),
                        x, y, input.cost(), revision, input.eventTime(),
                        expiresAt, now, false));
            }
        }
        for (ObstacleRow row : rows) {
            repository.insertObstacle(meta.version(), row);
        }
        // 只取代同一来源在同区域内的旧 revision，其他来源与更晚数据不受影响
        repository.markSupersededInRegion(mapId, input.sourceId(), input.region(), revision);

        return new UpdateResult(input.requestId(), false, revision,
                rows.size(), meta.version(), now);
    }

    @Override
    public CellView viewRegion(String mapId, Region region) {
        MapMeta meta = requireLatestMap(mapId);
        requireFits(region, meta);
        return assembleLiveView(meta, region, clock.now());
    }

    @Override
    public int cleanupExpired(String mapId) {
        requireLatestMap(mapId);
        Integer deleted = txTemplate.execute(
                status -> repository.deleteExpired(mapId, clock.now()));
        return deleted == null ? 0 : deleted;
    }

    @Override
    public SnapshotInfo publishSnapshot(String mapId, String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new InvalidRegionException("snapshotId must not be blank");
        }
        MapMeta meta = requireLatestMap(mapId);
        long now = clock.now();
        try {
            return txTemplate.execute(status -> {
                Region whole = new Region(0, 0, meta.width(), meta.height());
                List<SnapshotCell> cells = computeLayeredCells(meta, whole, now).stream()
                        .filter(c -> c.finalCost() != 0)
                        .toList();
                repository.insertSnapshot(snapshotId, mapId, meta.version(), now);
                repository.insertSnapshotCells(snapshotId, cells);
                return new SnapshotInfo(snapshotId, mapId, meta.version(), now);
            });
        } catch (DuplicateKeyException e) {
            throw new ConflictException("SNAPSHOT_EXISTS",
                    "snapshot already exists: " + snapshotId);
        }
    }

    @Override
    public CellView viewSnapshotRegion(String snapshotId, Region region) {
        SnapshotRecord snapshot = requireSnapshot(snapshotId);
        MapMeta meta = requireVersion(snapshot.mapId(), snapshot.mapVersion());
        requireFits(region, meta);
        List<SnapshotCell> stored =
                repository.loadSnapshotCellsInRegion(snapshotId, region);
        Map<CellKey, SnapshotCell> byKey = new HashMap<>();
        stored.forEach(c -> byKey.put(new CellKey(c.x(), c.y()), c));
        List<CellView.CellViewItem> items = new ArrayList<>(region.cellCount());
        for (int y = region.y0(); y < region.y1(); y++) {
            for (int x = region.x0(); x < region.x1(); x++) {
                SnapshotCell c = byKey.get(new CellKey(x, y));
                if (c == null) {
                    items.add(new CellView.CellViewItem(x, y, 0, null, 0, 0, null, null));
                } else {
                    items.add(new CellView.CellViewItem(x, y, c.staticCost(), c.obstacleCost(),
                            c.inflatedCost(), c.finalCost(), c.winnerSourceId(), c.winnerType()));
                }
            }
        }
        return new CellView(snapshot.mapId(), snapshot.mapVersion(), region, items);
    }

    @Override
    public RegionDiff diffRegion(String mapId, String fromSnapshot, String toSnapshot,
                                 Region region) {
        MapMeta meta = requireLatestMap(mapId);
        requireFits(region, meta);
        SnapshotRecord from = requireSnapshot(fromSnapshot);
        if (!mapId.equals(from.mapId())) {
            throw new ConflictException("VERSION_CONFLICT",
                    "snapshot " + fromSnapshot + " does not belong to map " + mapId);
        }
        requireVersion(from.mapId(), from.mapVersion());

        CellView fromView = viewSnapshotRegion(fromSnapshot, region);
        CellView toView;
        String effectiveTo;
        if (toSnapshot == null || toSnapshot.isBlank()) {
            toView = assembleLiveView(meta, region, clock.now());
            effectiveTo = "LIVE@" + meta.version();
        } else {
            SnapshotRecord to = requireSnapshot(toSnapshot);
            if (!mapId.equals(to.mapId())) {
                throw new ConflictException("VERSION_CONFLICT",
                        "snapshot " + toSnapshot + " does not belong to map " + mapId);
            }
            toView = viewSnapshotRegion(toSnapshot, region);
            effectiveTo = toSnapshot;
        }

        Map<CellKey, CellView.CellViewItem> fromByKey = indexView(fromView);
        List<RegionDiff.DiffCell> changed = new ArrayList<>();
        for (CellView.CellViewItem to : toView.cells()) {
            CellView.CellViewItem fromCell = fromByKey.get(new CellKey(to.x(), to.y()));
            int fromCost = fromCell == null ? 0 : fromCell.finalCost();
            if (fromCost != to.finalCost()) {
                changed.add(new RegionDiff.DiffCell(to.x(), to.y(), fromCost, to.finalCost()));
            }
        }
        return new RegionDiff(mapId, region, fromSnapshot, effectiveTo, changed);
    }

    // ------------------------------------------------------------------
    // 内部计算
    // ------------------------------------------------------------------

    private CellView assembleLiveView(MapMeta meta, Region region, long now) {
        List<SnapshotCell> layered = computeLayeredCells(meta, region, now);
        List<CellView.CellViewItem> items = new ArrayList<>(layered.size());
        for (SnapshotCell c : layered) {
            items.add(new CellView.CellViewItem(c.x(), c.y(), c.staticCost(), c.obstacleCost(),
                    c.inflatedCost(), c.finalCost(), c.winnerSourceId(), c.winnerType()));
        }
        return new CellView(meta.mapId(), meta.version(), region, items);
    }

    /**
     * 计算 region 内每格分层代价。
     *
     * <p>膨胀取数包围盒为 region 向四周扩张 inflationRadiusCells 并裁剪到地图边界，
     * 因此无论更新落在哪里，查询都只会读取受影响包围盒内的障碍，而不重建整图。</p>
     */
    private List<SnapshotCell> computeLayeredCells(MapMeta meta, Region region, long now) {
        Region bbox = region.expandAndClip(meta.inflationRadiusCells(),
                meta.width(), meta.height());
        List<ObstacleRow> live = repository.loadLiveObstacles(meta.mapId(), bbox, now);
        Map<CellKey, ObstacleRow> winnerMap = LayerMerge.selectWinners(live);

        // 静态层在膨胀包围盒内取数：致命格（cost=100）同样作为膨胀源（Nav2 语义），
        // 但不进入动态获胜层
        List<CellCost> staticInBbox =
                repository.loadStaticCellsInRegion(meta.mapId(), meta.version(), bbox);
        Map<CellKey, Integer> staticByKey = new HashMap<>();
        List<ObstacleRow> inflationSources = new ArrayList<>(winnerMap.values());
        for (CellCost c : staticInBbox) {
            staticByKey.put(new CellKey(c.x(), c.y()), c.cost());
            if (c.cost() >= 100) {
                inflationSources.add(new ObstacleRow(
                        meta.mapId(), "__STATIC__", "__STATIC__", SourceType.SAFETY,
                        c.x(), c.y(), 100, 0L, 0L, null, 0L, false));
            }
        }
        int[] inflated = InflationCalculator.inflate(
                region, inflationSources, meta.inflationRadiusCells(), meta.costDecay());

        List<SnapshotCell> out = new ArrayList<>(region.cellCount());
        int idx = 0;
        for (int y = region.y0(); y < region.y1(); y++) {
            for (int x = region.x0(); x < region.x1(); x++) {
                CellKey key = new CellKey(x, y);
                int staticCost = staticByKey.getOrDefault(key, 0);
                ObstacleRow winner = winnerMap.get(key);
                Integer obstacleCost = winner == null ? null : winner.cost();
                int inflatedCost = inflated[idx++];
                int finalCost = LayerMerge.combine(staticCost, obstacleCost, inflatedCost);
                boolean trivial = staticCost == 0 && winner == null && inflatedCost == 0;
                if (trivial) {
                    // 快照/视图统一保留全格行；快照写库时再过滤
                    out.add(new SnapshotCell(x, y, 0, null, 0, 0, null, null));
                } else {
                    out.add(new SnapshotCell(x, y, staticCost, obstacleCost, inflatedCost,
                            finalCost,
                            winner == null ? null : winner.sourceId(),
                            winner == null ? null : winner.sourceType().name()));
                }
            }
        }
        return out;
    }

    private Map<CellKey, CellView.CellViewItem> indexView(CellView view) {
        Map<CellKey, CellView.CellViewItem> map = new HashMap<>();
        for (CellView.CellViewItem item : view.cells()) {
            map.put(new CellKey(item.x(), item.y()), item);
        }
        return map;
    }

    private MapMeta requireLatestMap(String mapId) {
        MapMeta meta = repository.findLatestVersion(mapId);
        if (meta == null) {
            throw new VersionNotFoundException("map not found: " + mapId);
        }
        return meta;
    }

    private MapMeta requireVersion(String mapId, long version) {
        MapMeta meta = repository.findVersion(mapId, version);
        if (meta == null) {
            throw new VersionNotFoundException(
                    "map version not found: " + mapId + "@" + version);
        }
        return meta;
    }

    private SnapshotRecord requireSnapshot(String snapshotId) {
        SnapshotRecord snapshot = repository.findSnapshot(snapshotId);
        if (snapshot == null) {
            throw new VersionNotFoundException("snapshot not found: " + snapshotId);
        }
        return snapshot;
    }

    private void requireFits(Region region, MapMeta meta) {
        if (region == null || !region.isValid()) {
            throw new InvalidRegionException("region must be a non-empty rectangle: "
                    + (region == null ? "null" : regionText(region)));
        }
        if (!region.fitsIn(meta.width(), meta.height())) {
            throw new InvalidRegionException(
                    "region " + regionText(region) + " is outside the map bounds "
                            + meta.width() + "x" + meta.height());
        }
    }

    private void validateCreateCommand(CreateMapCommand command) {
        if (command.mapId() == null || command.mapId().isBlank()) {
            throw new InvalidRegionException("mapId must not be blank");
        }
        if (command.width() <= 0 || command.height() <= 0) {
            throw new InvalidRegionException("width and height must be positive");
        }
        if (command.resolution() <= 0) {
            throw new InvalidRegionException("resolution must be positive");
        }
        if (command.inflationRadiusCells() < 0) {
            throw new InvalidRegionException("inflationRadiusCells must be >= 0");
        }
        if (command.costDecay() <= 0 || command.costDecay() > 1.0) {
            throw new InvalidRegionException("costDecay must be within (0, 1]");
        }
        Map<CellKey, Integer> seen = new HashMap<>();
        for (CellCost c : command.staticCells()) {
            if (c.x() < 0 || c.x() >= command.width() || c.y() < 0 || c.y() >= command.height()) {
                throw new InvalidRegionException(
                        "static cell (" + c.x() + "," + c.y() + ") is outside the map bounds "
                                + command.width() + "x" + command.height());
            }
            if (c.cost() < 0 || c.cost() > 100) {
                throw new InvalidRegionException(
                        "static cell cost must be within [0,100]: " + c.cost());
            }
            CellKey key = new CellKey(c.x(), c.y());
            if (seen.containsKey(key)) {
                throw new InvalidRegionException(
                        "duplicated static cell: (" + c.x() + "," + c.y() + ")");
            }
            seen.put(key, c.cost());
        }
    }

    private void validateInput(ObstacleInput input) {
        if (input == null) {
            throw new InvalidRegionException("request body is required");
        }
        if (input.requestId() == null || input.requestId().isBlank()) {
            throw new InvalidRegionException("requestId must not be blank");
        }
        if (input.sourceId() == null || input.sourceId().isBlank()) {
            throw new InvalidRegionException("sourceId must not be blank");
        }
        if (input.sourceType() == null) {
            throw new InvalidRegionException("sourceType must not be blank");
        }
        if (input.cost() < 0 || input.cost() > 100) {
            throw new InvalidRegionException("cost must be within [0,100]");
        }
        if (input.region() == null || !input.region().isValid()) {
            throw new InvalidRegionException("region must be a non-empty rectangle: "
                    + (input.region() == null ? "null" : regionText(input.region())));
        }
        if (input.ttlMillis() != null && input.ttlMillis() <= 0) {
            throw new InvalidRegionException("ttlMillis must be positive or null");
        }
    }

    private static String regionText(Region r) {
        return "[" + r.x0() + "," + r.y0() + "," + r.x1() + "," + r.y1() + ")";
    }

    /**
     * 规范化请求参数指纹（不含 expectedVersion，版本校验只影响本次应用条件）。
     */
    static String fingerprint(ObstacleInput input) {
        String canonical = String.join("|",
                input.sourceId(),
                input.sourceType().name(),
                Integer.toString(input.region().x0()),
                Integer.toString(input.region().y0()),
                Integer.toString(input.region().x1()),
                Integer.toString(input.region().y1()),
                Integer.toString(input.cost()),
                Long.toString(input.eventTime()),
                input.ttlMillis() == null ? "" : Long.toString(input.ttlMillis()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
