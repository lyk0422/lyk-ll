package com.example.starter.costmap.web;

import com.example.starter.costmap.service.CostmapService;
import com.example.starter.costmap.web.dto.CellRegionResponse;
import com.example.starter.costmap.web.dto.CreateMapRequest;
import com.example.starter.costmap.web.dto.MapResponse;
import com.example.starter.costmap.web.dto.ObstacleRequest;
import com.example.starter.costmap.web.dto.ObstacleResponse;
import com.example.starter.costmap.web.dto.PurgeResponse;
import com.example.starter.costmap.web.dto.RegionDiffResponse;
import com.example.starter.costmap.web.dto.RegionDto;
import com.example.starter.costmap.web.dto.RestoreResponse;
import com.example.starter.costmap.web.dto.SnapshotRequest;
import com.example.starter.costmap.web.dto.SnapshotResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nav2 风格动态 Costmap REST 入口。
 */
@RestController
@RequestMapping("/api/costmaps")
public class CostmapController {

    private final CostmapService service;

    public CostmapController(CostmapService service) {
        this.service = service;
    }

    /** 创建不可变地图版本。 */
    @PostMapping
    public ResponseEntity<MapResponse> createMap(@Valid @RequestBody CreateMapRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createMap(request));
    }

    /** 查询地图元数据。 */
    @GetMapping("/{mapId}")
    public MapResponse getMap(@PathVariable String mapId) {
        return service.getMap(mapId);
    }

    /** 查询当前动态视图的区域格栅，参数 x0/y0/x1/y1。 */
    @GetMapping("/{mapId}/regions")
    public CellRegionResponse queryRegion(@PathVariable String mapId,
                                          @RequestParam int x0,
                                          @RequestParam int y0,
                                          @RequestParam int x1,
                                          @RequestParam int y1) {
        return service.queryRegion(mapId, new RegionDto(x0, y0, x1, y1));
    }

    /** 应用障碍物更新。 */
    @PostMapping("/{mapId}/obstacles")
    public ObstacleResponse applyObstacle(@PathVariable String mapId,
                                          @Valid @RequestBody ObstacleRequest request) {
        return service.applyObstacle(mapId, request);
    }

    /** 主动触发过期清理。 */
    @PostMapping("/{mapId}/purge-expired")
    public PurgeResponse purgeExpired(@PathVariable String mapId) {
        return service.purgeExpired(mapId);
    }

    /** 发布版本快照。 */
    @PostMapping("/{mapId}/snapshots")
    public SnapshotResponse publishSnapshot(@PathVariable String mapId,
                                            @Valid @RequestBody(required = false) SnapshotRequest request) {
        return service.publishSnapshot(mapId, request);
    }

    /** 查询快照元数据。 */
    @GetMapping("/{mapId}/snapshots/{snapshotId}")
    public SnapshotResponse getSnapshot(@PathVariable String mapId,
                                        @PathVariable String snapshotId) {
        return service.getSnapshot(mapId, snapshotId);
    }

    /** 查询快照固化视图的区域格栅。 */
    @GetMapping("/{mapId}/snapshots/{snapshotId}/regions")
    public CellRegionResponse querySnapshotRegion(@PathVariable String mapId,
                                                  @PathVariable String snapshotId,
                                                  @RequestParam int x0,
                                                  @RequestParam int y0,
                                                  @RequestParam int x1,
                                                  @RequestParam int y1) {
        return service.querySnapshotRegion(mapId, snapshotId, new RegionDto(x0, y0, x1, y1));
    }

    /** 查询快照与当前视图的区域差异。 */
    @GetMapping("/{mapId}/snapshots/{snapshotId}/diff")
    public RegionDiffResponse diffRegion(@PathVariable String mapId,
                                         @PathVariable String snapshotId,
                                         @RequestParam int x0,
                                         @RequestParam int y0,
                                         @RequestParam int x1,
                                         @RequestParam int y1) {
        return service.diffRegion(mapId, snapshotId, new RegionDto(x0, y0, x1, y1));
    }

    /** 从 H2 保存的快照恢复当前查询视图。 */
    @PostMapping("/{mapId}/snapshots/{snapshotId}/restore")
    public RestoreResponse restoreSnapshot(@PathVariable String mapId,
                                           @PathVariable String snapshotId) {
        return service.restoreSnapshot(mapId, snapshotId);
    }
}
