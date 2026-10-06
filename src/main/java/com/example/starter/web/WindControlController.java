package com.example.starter.web;

import com.example.starter.service.ControlEventService;
import com.example.starter.service.WindIngestionService;
import com.example.starter.web.dto.BoardingRequest;
import com.example.starter.web.dto.CabinReleaseRequest;
import com.example.starter.web.dto.ClearanceRequest;
import com.example.starter.web.dto.EventView;
import com.example.starter.web.dto.LineView;
import com.example.starter.web.dto.ReopenApplicationRequest;
import com.example.starter.web.dto.ReopenConfirmationRequest;
import com.example.starter.web.dto.WindReadingRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 索道运行管制接口。
 */
@RestController
@RequestMapping("/api")
public class WindControlController {

    private final WindIngestionService windIngestionService;
    private final ControlEventService controlEventService;

    public WindControlController(WindIngestionService windIngestionService,
                                 ControlEventService controlEventService) {
        this.windIngestionService = windIngestionService;
        this.controlEventService = controlEventService;
    }

    /**
     * 上报风速数据（按站点与时刻去重，乱序归位，按需触发管制判定）。
     */
    @PostMapping("/lines/{lineId}/wind-readings")
    public ResponseEntity<String> ingestWindReading(@PathVariable long lineId,
                                                    @Valid @RequestBody WindReadingRequest request) {
        return windIngestionService.ingest(lineId, request);
    }

    /**
     * 上客（管制期间禁止）。
     */
    @PostMapping("/lines/{lineId}/boardings")
    public ResponseEntity<String> board(@PathVariable long lineId,
                                        @Valid @RequestBody BoardingRequest request) {
        return controlEventService.board(lineId, request);
    }

    /**
     * 查询线路状态。
     */
    @GetMapping("/lines/{lineId}")
    public LineView line(@PathVariable long lineId) {
        return controlEventService.lineView(lineId);
    }

    /**
     * 缓停在途吊厢按站点逐段放行。
     */
    @PostMapping("/events/{eventId}/segments/{segmentId}/cabin-releases")
    public ResponseEntity<String> releaseCabins(@PathVariable long eventId, @PathVariable long segmentId,
                                                @Valid @RequestBody CabinReleaseRequest request) {
        return controlEventService.releaseCabins(eventId, segmentId, request);
    }

    /**
     * 紧急停车救援分段清空确认（须双人确认）。
     */
    @PostMapping("/events/{eventId}/segments/{segmentId}/clearance-confirmations")
    public ResponseEntity<String> confirmClearance(@PathVariable long eventId, @PathVariable long segmentId,
                                                   @Valid @RequestBody ClearanceRequest request) {
        return controlEventService.confirmClearance(eventId, segmentId, request);
    }

    /**
     * 申请复开（风速回落至 15m/s 以下并持续 15 分钟）。
     */
    @PostMapping("/events/{eventId}/reopen-applications")
    public ResponseEntity<String> applyReopen(@PathVariable long eventId,
                                              @Valid @RequestBody ReopenApplicationRequest request) {
        return controlEventService.applyReopen(eventId, request);
    }

    /**
     * 复开双岗确认（运营与机务）。
     */
    @PostMapping("/events/{eventId}/reopen-confirmations")
    public ResponseEntity<String> confirmReopen(@PathVariable long eventId,
                                                @Valid @RequestBody ReopenConfirmationRequest request) {
        return controlEventService.confirmReopen(eventId, request);
    }

    /**
     * 查询管制事件。
     */
    @GetMapping("/events/{eventId}")
    public EventView event(@PathVariable long eventId) {
        return controlEventService.eventView(eventId);
    }
}
