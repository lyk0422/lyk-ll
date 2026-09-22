package com.example.starter.sync.web;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.sync.SensorType;
import com.example.starter.sync.domain.DriftParams;
import com.example.starter.sync.service.DriftService;
import com.example.starter.sync.service.FusionService;
import com.example.starter.sync.service.RebuildService;
import com.example.starter.sync.service.SensorSyncService;

/**
 * 多传感器时间同步与融合帧 API。
 */
@RestController
@RequestMapping("/api/sync")
public class SensorSyncController {

    private final SensorSyncService sensorSyncService;
    private final FusionService fusionService;
    private final DriftService driftService;
    private final RebuildService rebuildService;

    public SensorSyncController(SensorSyncService sensorSyncService, FusionService fusionService,
                                DriftService driftService, RebuildService rebuildService) {
        this.sensorSyncService = sensorSyncService;
        this.fusionService = fusionService;
        this.driftService = driftService;
        this.rebuildService = rebuildService;
    }

    /**
     * 接收一条传感器消息（幂等）。
     */
    @PostMapping("/messages")
    public IngestMessageResponse ingest(@Valid @RequestBody IngestMessageRequest request) {
        return IngestMessageResponse.of(sensorSyncService.ingest(
                request.sensorType(), request.messageId(), request.rawTs()));
    }

    /**
     * 提交目标时刻的融合帧。
     */
    @PostMapping("/frames/commit")
    public FusionFrameView commit(@Valid @RequestBody CommitFrameRequest request) {
        return FusionFrameView.of(fusionService.commit(request.targetTs()));
    }

    /**
     * 查询已提交的融合帧。
     */
    @GetMapping("/frames/{targetTs}")
    public FusionFrameView getFrame(@PathVariable long targetTs) {
        return FusionFrameView.of(fusionService.getFrame(targetTs));
    }

    /**
     * 从 H2 事件记录重建指定时刻的融合帧及输入证据。
     */
    @PostMapping("/frames/{targetTs}/rebuild")
    public FusionFrameView rebuild(@PathVariable long targetTs) {
        return FusionFrameView.of(rebuildService.rebuild(targetTs));
    }

    /**
     * 热更新某传感器的时钟漂移校正参数。
     */
    @PutMapping("/drift/{sensorType}")
    public DriftParams updateDrift(@PathVariable SensorType sensorType,
                                   @Valid @RequestBody DriftUpdateRequest request) {
        return driftService.update(sensorType, request.version(), request.offsetMs(),
                request.effectiveFrom());
    }
}
