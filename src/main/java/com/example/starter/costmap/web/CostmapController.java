package com.example.starter.costmap.web;

import com.example.starter.costmap.cost.CostmapService;
import com.example.starter.costmap.domain.CellCost;
import com.example.starter.costmap.domain.CellView;
import com.example.starter.costmap.domain.MapMeta;
import com.example.starter.costmap.domain.ObstacleInput;
import com.example.starter.costmap.domain.Region;
import com.example.starter.costmap.domain.SourceType;
import com.example.starter.costmap.domain.UpdateResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 动态 Costmap HTTP 接口。
 */
@RestController
@RequestMapping("/api/costmaps")
public class CostmapController {

    private final CostmapService service;

    public CostmapController(CostmapService service) {
        this.service = service;
    }

    /** 创建地图首个不可变版本。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MapMeta createMap(@Valid @RequestBody CreateMapRequest request) {
        List<CellCost> cells = request.staticCells().stream()
                .map(c -> new CellCost(c.x(), c.y(), c.cost()))
                .toList();
        return service.createMap(new CostmapService.CreateMapCommand(
                request.mapId(),
                request.width(),
                request.height(),
                request.resolution(),
                request.originX(),
                request.originY(),
                request.inflationRadiusCells(),
                request.costDecay(),
                cells));
    }

    /** 应用一次障碍物更新。 */
    @PostMapping("/{mapId}/obstacles")
    public UpdateResult updateObstacle(@PathVariable String mapId,
                                       @Valid @RequestBody ObstacleUpdateRequest request) {
        SourceType sourceType = SourceType.fromString(request.sourceType());
        Region region = new Region(
                request.region().x0(), request.region().y0(),
                request.region().x1(), request.region().y1());
        ObstacleInput input = new ObstacleInput(
                request.requestId(),
                request.sourceId(),
                sourceType,
                region,
                request.cost(),
                request.eventTime(),
                request.ttlMillis(),
                request.expectedVersion());
        return service.updateObstacle(mapId, input);
    }

    /** 查询实时视图区域。 */
    @PostMapping("/{mapId}/view")
    public CellView viewRegion(@PathVariable String mapId,
                               @RequestBody ObstacleUpdateRequest.RegionDto region) {
        return service.viewRegion(mapId, toRegion(region));
    }

    /** 触发 TTL 过期清理。 */
    @PostMapping("/{mapId}/cleanup")
    public CleanupResponse cleanup(@PathVariable String mapId) {
        return new CleanupResponse(mapId, service.cleanupExpired(mapId));
    }

    /** 发布不可变快照。 */
    @PostMapping("/{mapId}/snapshots/{snapshotId}")
    @ResponseStatus(HttpStatus.CREATED)
    public CostmapService.SnapshotInfo publishSnapshot(@PathVariable String mapId,
                                                       @PathVariable String snapshotId) {
        return service.publishSnapshot(mapId, snapshotId);
    }

    /** 从快照恢复只读查询视图。 */
    @PostMapping("/snapshots/{snapshotId}/view")
    public CellView viewSnapshot(@PathVariable String snapshotId,
                                 @RequestBody ObstacleUpdateRequest.RegionDto region) {
        return service.viewSnapshotRegion(snapshotId, toRegion(region));
    }

    /** 区域差异：from 快照对比 to 快照（to 缺省对比当前实时视图）。 */
    @PostMapping("/{mapId}/diff")
    public CostmapService.RegionDiff diff(
            @PathVariable String mapId,
            @RequestParam String fromSnapshot,
            @RequestParam(required = false) String toSnapshot,
            @RequestBody ObstacleUpdateRequest.RegionDto region) {
        return service.diffRegion(mapId, fromSnapshot, toSnapshot, toRegion(region));
    }

    private static Region toRegion(ObstacleUpdateRequest.RegionDto r) {
        return new Region(r.x0(), r.y0(), r.x1(), r.y1());
    }

    /** 清理响应。 */
    public record CleanupResponse(String mapId, int deletedRows) {
    }
}
