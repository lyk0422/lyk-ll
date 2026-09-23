package com.example.starter.playout.web;

import com.example.starter.playout.service.PlayoutService;
import com.example.starter.playout.service.ServiceResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 频道播出编排 HTTP 接口；所有时间使用 UTC（ISO-8601，如 2026-01-01T00:00:00Z）。
 * 带幂等键的动作（插播开始/结束、ACK）按首次冻结状态码回放。
 */
@RestController
public class PlayoutController {

    private final PlayoutService service;

    public PlayoutController(PlayoutService service) {
        this.service = service;
    }

    @PostMapping("/api/channels/playlists/publish")
    public PlaylistPublishResponse publish(@Valid @RequestBody PlaylistPublishRequest request) {
        return service.publishPlaylist(request);
    }

    @PostMapping("/api/channels/tick")
    public TickResponse tick(@Valid @RequestBody TickRequest request) {
        return service.tick(request);
    }

    @PostMapping("/api/channels/live/start")
    public ResponseEntity<Object> liveStart(@Valid @RequestBody LiveStartRequest request) {
        return status(service.startLive(request));
    }

    @PostMapping("/api/channels/live/end")
    public ResponseEntity<Object> liveEnd(@Valid @RequestBody LiveEndRequest request) {
        return status(service.endLive(request));
    }

    @PostMapping("/api/channels/ack")
    public ResponseEntity<Object> ack(@Valid @RequestBody AckRequest request) {
        return status(service.ack(request));
    }

    @GetMapping("/api/channels/{channel}/as-run")
    public List<AsRunView> asRun(@PathVariable String channel,
                                 @RequestParam(required = false) Instant from,
                                 @RequestParam(required = false) Instant to) {
        return service.queryAsRun(channel, from, to);
    }

    private static ResponseEntity<Object> status(ServiceResult<Object> result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
