package com.example.starter.costmap.web;

import com.example.starter.costmap.model.GridRegion;
import com.example.starter.costmap.service.CostmapService;
import com.example.starter.costmap.web.dto.CellChange;
import com.example.starter.costmap.web.dto.CleanupResponse;
import com.example.starter.costmap.web.dto.CreateMapRequest;
import com.example.starter.costmap.web.dto.MapResponse;
import com.example.starter.costmap.web.dto.ObstacleUpdateRequest;
import com.example.starter.costmap.web.dto.RegionCostResponse;
import com.example.starter.costmap.web.dto.RegionRequest;
import com.example.starter.costmap.web.dto.RestoreViewResponse;
import com.example.starter.costmap.web.dto.SnapshotResponse;
import com.example.starter.costmap.web.dto.UpdateResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动态代价地图 REST 入口（骨架，随服务逐步填充）。
 */
@RestController
public class CostmapController {

    private final CostmapService service;

    public CostmapController(CostmapService service) {
        this.service = service;
    }

    @PostMapping("/api/maps")
    @ResponseStatus(HttpStatus.CREATED)
    public MapResponse createMap(@Valid @RequestBody CreateMapRequest request) {
        return service.createMap(request);
    }

    @PostMapping("/api/maps/{versionId}/obstacles")
    public UpdateResponse updateObstacle(@PathVariable String versionId,
                                         @Valid @RequestBody ObstacleUpdateRequest request) {
        return service.updateObstacle(versionId, request);
    }

    @PostMapping("/api/maps/{versionId}/regions/query")
    public RegionCostResponse queryRegion(@PathVariable String versionId,
                                          @Valid @RequestBody RegionRequest request) {
        return service.queryRegion(versionId, toRegion(request));
    }

    @PostMapping("/api/maps/{versionId}/refs/{ref}/regions/query")
    public RegionCostResponse queryRef(@PathVariable String versionId,
                                       @PathVariable String ref,
                                       @Valid @RequestBody RegionRequest request) {
        return service.queryRef(versionId, ref, toRegion(request));
    }

    @PostMapping("/api/maps/{versionId}/snapshots")
    public SnapshotResponse publishSnapshot(@PathVariable String versionId,
                                            @RequestParam(required = false) String snapshotId) {
        return service.publishSnapshot(versionId, snapshotId);
    }

    @PostMapping("/api/maps/{versionId}/diff")
    public CellChange.DiffResponse diff(@PathVariable String versionId,
                                        @RequestParam String from,
                                        @RequestParam String to,
                                        @Valid @RequestBody RegionRequest request) {
        return service.diff(versionId, from, to, toRegion(request));
    }

    @PostMapping("/api/snapshots/{snapshotId}/restore-view")
    @ResponseStatus(HttpStatus.CREATED)
    public RestoreViewResponse restoreView(@PathVariable String snapshotId) {
        return service.restoreView(snapshotId);
    }

    @GetMapping("/api/views/{viewId}")
    public RegionCostResponse queryView(@PathVariable String viewId,
                                        @RequestParam int xMin,
                                        @RequestParam int yMin,
                                        @RequestParam int xMax,
                                        @RequestParam int yMax) {
        return service.queryViewById(viewId,
                new GridRegion(xMin, yMin, xMax, yMax));
    }

    @PostMapping("/api/maps/{versionId}/cleanup")
    public CleanupResponse cleanup(@PathVariable String versionId,
                                   @RequestParam(required = false) Long atMs) {
        return service.cleanup(versionId, atMs);
    }

    private GridRegion toRegion(RegionRequest request) {
        return new GridRegion(request.xMin(), request.yMin(), request.xMax(), request.yMax());
    }
}
