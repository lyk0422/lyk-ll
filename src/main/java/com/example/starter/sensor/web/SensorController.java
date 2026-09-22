package com.example.starter.sensor.web;

import com.example.starter.sensor.api.CorrectionRequest;
import com.example.starter.sensor.api.CorrectionResponse;
import com.example.starter.sensor.api.DroppedMessageView;
import com.example.starter.sensor.api.FrameRequest;
import com.example.starter.sensor.api.FrameView;
import com.example.starter.sensor.api.IngestMessageRequest;
import com.example.starter.sensor.api.IngestMessageResponse;
import com.example.starter.sensor.domain.SensorType;
import com.example.starter.sensor.repo.SyncRepository;
import com.example.starter.sensor.service.ReplayService;
import com.example.starter.sensor.service.SyncService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 多传感器时间同步与融合帧 REST 入口。
 */
@RestController
@RequestMapping("/api/sensors")
public class SensorController {

    private final SyncService syncService;
    private final ReplayService replayService;
    private final SyncRepository repository;

    public SensorController(SyncService syncService,
                            ReplayService replayService,
                            SyncRepository repository) {
        this.syncService = syncService;
        this.replayService = replayService;
        this.repository = repository;
    }

    /** 摄取某传感器的原始消息。 */
    @PostMapping("/{sensor}/messages")
    public IngestMessageResponse ingest(@PathVariable String sensor,
                                        @Valid @RequestBody IngestMessageRequest request) {
        return syncService.ingest(SensorType.valueOf(sensor.toUpperCase()), request);
    }

    /** 查询某传感器当前事件时间水位线。 */
    @GetMapping("/{sensor}/watermark")
    public java.util.Map<String, Object> watermark(@PathVariable String sensor) {
        SensorType type = SensorType.valueOf(sensor.toUpperCase());
        return java.util.Map.of("sensor", sensor.toUpperCase(), "watermark", syncService.watermarkOf(type));
    }

    /** 热更新某传感器的时钟漂移校正参数。 */
    @PostMapping("/{sensor}/corrections")
    public CorrectionResponse updateCorrection(@PathVariable String sensor,
                                               @Valid @RequestBody CorrectionRequest request) {
        return syncService.updateCorrection(SensorType.valueOf(sensor.toUpperCase()), request);
    }

    /** 查询某传感器的校正参数版本链。 */
    @GetMapping("/{sensor}/corrections")
    public List<CorrectionResponse> corrections(@PathVariable String sensor) {
        return syncService.listCorrections(SensorType.valueOf(sensor.toUpperCase()));
    }

    /** 查询某传感器的丢弃（迟到/重复）记录。 */
    @GetMapping("/{sensor}/dropped")
    public List<DroppedMessageView> dropped(@PathVariable String sensor) {
        return repository.findDropped(sensor.toUpperCase()).stream()
                .map(r -> new DroppedMessageView(r.id(), r.sensor(), r.messageId(),
                        r.rawTs(), r.receivedAt(), r.reason(), r.reasonDetail()))
                .toList();
    }

    /** 按目标融合时刻构建（或取回已提交的）融合帧。 */
    @PostMapping("/frames")
    public FrameView buildFrame(@Valid @RequestBody FrameRequest request) {
        boolean enforce = !Boolean.FALSE.equals(request.forceWait());
        return syncService.buildFrame(request.targetTimestamp(), enforce);
    }

    /** 查询已提交的融合帧；不存在时返回 404。 */
    @GetMapping("/frames/{targetTimestamp}")
    public FrameView getFrame(@PathVariable long targetTimestamp) {
        return syncService.getOrBuildFrame(targetTimestamp);
    }

    /**
     * 从 H2 事件记录重建指定时刻融合帧，不落库；支持校正版本截止线（事件重放幂等）。
     */
    @PostMapping("/frames/{targetTimestamp}/reconstruct")
    public FrameView reconstruct(@PathVariable long targetTimestamp,
                                 @RequestParam(required = false) Long correctionCutoffMs) {
        return replayService.reconstruct(targetTimestamp, correctionCutoffMs);
    }
}
