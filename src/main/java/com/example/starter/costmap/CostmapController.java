package com.example.starter.costmap;

import com.example.starter.costmap.dto.CleanupResponse;
import com.example.starter.costmap.dto.CreateMapRequest;
import com.example.starter.costmap.dto.DiffResponse;
import com.example.starter.costmap.dto.MapView;
import com.example.starter.costmap.dto.ObstacleUpdateRequest;
import com.example.starter.costmap.dto.ObstacleUpdateResponse;
import com.example.starter.costmap.dto.SnapshotView;
import com.example.starter.costmap.model.Region;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 代价地图 REST API。
 */
@RestController
@RequestMapping("/api/maps")
public class CostmapController {

    private final CostmapService service;

    public CostmapController(CostmapService service) {
        this.service = service;
    }

    /**
     * 创建地图。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MapView createMap(@RequestBody CreateMapRequest request) {
        return service.createMap(request);
    }

    /**
     * 读取当前版本视图。
     */
    @GetMapping("/{mapId}")
    public MapView currentView(@PathVariable String mapId) {
        return service.currentView(mapId);
    }

    /**
     * 读取指定版本视图。
     */
    @GetMapping("/{mapId}/versions/{version}")
    public MapView versionView(@PathVariable String mapId, @PathVariable long version) {
        return service.versionView(mapId, version);
    }

    /**
     * 提交障碍物更新（幂等）。
     */
    @PostMapping("/{mapId}/obstacles")
    public ObstacleUpdateResponse applyObstacle(@PathVariable String mapId,
                                                @RequestBody ObstacleUpdateRequest request) {
        return service.applyObstacle(mapId, request);
    }

    /**
     * 查询两版本在区域内的差异；缺省区域为全图。
     */
    @GetMapping("/{mapId}/diff")
    public DiffResponse diff(@PathVariable String mapId,
                             @RequestParam long fromVersion,
                             @RequestParam long toVersion,
                             @RequestParam(required = false) Integer x,
                             @RequestParam(required = false) Integer y,
                             @RequestParam(required = false) Integer width,
                             @RequestParam(required = false) Integer height) {
        Region region = null;
        if (x != null || y != null || width != null || height != null) {
            if (x == null || y == null || width == null || height == null) {
                throw new IllegalArgumentException("x, y, width and height must be provided together");
            }
            region = new Region(x, y, width, height);
        }
        return service.diff(mapId, fromVersion, toVersion, region);
    }

    /**
     * 发布版本快照（body 可省略，缺省当前版本）。
     */
    @PostMapping("/{mapId}/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotView publishSnapshot(@PathVariable String mapId,
                                        @RequestBody(required = false) PublishSnapshotRequest request) {
        return service.publishSnapshot(mapId, request == null ? null : request.version());
    }

    /**
     * 读取快照。
     */
    @GetMapping("/{mapId}/snapshots/{version}")
    public SnapshotView snapshot(@PathVariable String mapId, @PathVariable long version) {
        return service.snapshot(mapId, version);
    }

    /**
     * 用快照恢复查询视图。
     */
    @PostMapping("/{mapId}/snapshots/{version}/restore")
    public MapView restoreSnapshot(@PathVariable String mapId, @PathVariable long version) {
        return service.restoreSnapshot(mapId, version);
    }

    /**
     * 触发过期障碍物清理。
     */
    @PostMapping("/{mapId}/cleanup")
    public CleanupResponse cleanup(@PathVariable String mapId) {
        return service.cleanup(mapId);
    }

    /**
     * 发布快照请求体。
     *
     * @param version 目标版本号；为空表示当前版本
     */
    public record PublishSnapshotRequest(Long version) {
    }
}
