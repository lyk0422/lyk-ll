package com.example.starter.perception.api;

import com.example.starter.perception.service.TrackService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 感知目标跟踪与轨迹状态机 REST 入口。
 */
@RestController
@RequestMapping("/api/tracks")
public class TrackController {

    private final TrackService trackService;

    public TrackController(TrackService trackService) {
        this.trackService = trackService;
    }

    /** 提交一批本地检测，原子完成关联与状态迁移。 */
    @PostMapping("/associations")
    @ResponseStatus(HttpStatus.OK)
    public AssociationResponse associate(@Valid @RequestBody AssociationRequest request) {
        return trackService.associate(request);
    }

    /** 查询单条轨迹当前态。 */
    @GetMapping("/{trackId}")
    public TrackSnapshotResponse get(@PathVariable String trackId) {
        return trackService.currentSnapshot(trackId);
    }

    /** 查询全部轨迹当前态。 */
    @GetMapping
    public List<TrackSnapshotResponse> list() {
        return trackService.currentSnapshots();
    }

    /**
     * 从事件流重建指定时刻（含）的轨迹快照：GET /api/tracks/{id}/snapshot?at=ISO-8601。
     */
    @GetMapping("/{trackId}/snapshot")
    public TrackSnapshotResponse snapshotAt(@PathVariable String trackId, @RequestParam("at") Instant at) {
        return trackService.replaySnapshot(trackId, at);
    }

    /** 从事件流重建指定时刻全部轨迹快照。 */
    @GetMapping("/snapshots")
    public List<TrackSnapshotResponse> snapshotsAt(@RequestParam("at") Instant at) {
        return trackService.replaySnapshots(at);
    }

    /** 显式回收轨迹（LOST -&gt; RECYCLED）。 */
    @PostMapping("/{trackId}/recycle")
    public TrackSnapshotResponse recycle(@PathVariable String trackId,
                                         @RequestBody(required = false) RecycleRequest request) {
        Long expectedVersion = request == null ? null : request.expectedVersion();
        return trackService.recycle(trackId, expectedVersion);
    }
}
