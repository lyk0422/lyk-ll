package com.example.starter.api;

import com.example.starter.api.dto.ReopenConfirmationRequest;
import com.example.starter.api.dto.ReopenRequestBody;
import com.example.starter.api.dto.RescueConfirmationRequest;
import com.example.starter.api.dto.SegmentReleaseRequest;
import com.example.starter.service.ControlService;
import com.example.starter.service.IdempotencyService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 线路管制操作接口。
 */
@RestController
public class ControlController {

    private final ControlService controlService;

    public ControlController(ControlService controlService) {
        this.controlService = controlService;
    }

    /** 线路详情（含状态、是否允许上客、分段与进行中事件）。 */
    @GetMapping("/api/lines/{lineId}")
    public Map<String, Object> lineDetail(@PathVariable long lineId) {
        return controlService.lineDetail(lineId);
    }

    /** 管制事件详情。 */
    @GetMapping("/api/events/{eventId}")
    public Map<String, Object> eventDetail(@PathVariable long eventId) {
        return controlService.eventDetail(eventId);
    }

    /** 缓停放在途吊厢按站点逐段放行。 */
    @PostMapping("/api/events/{eventId}/segment-releases")
    public ResponseEntity<String> releaseSegment(@PathVariable long eventId,
                                                 @Valid @RequestBody SegmentReleaseRequest request) {
        return respond(controlService.releaseSegment(eventId, request));
    }

    /** 紧急停车救援分段清空确认（须双人）。 */
    @PostMapping("/api/events/{eventId}/rescue-confirmations")
    public ResponseEntity<String> confirmRescue(@PathVariable long eventId,
                                                @Valid @RequestBody RescueConfirmationRequest request) {
        return respond(controlService.confirmRescue(eventId, request));
    }

    /** 申请复开。 */
    @PostMapping("/api/events/{eventId}/reopen-requests")
    public ResponseEntity<String> requestReopen(@PathVariable long eventId,
                                                @Valid @RequestBody ReopenRequestBody request) {
        return respond(controlService.requestReopen(eventId, request));
    }

    /** 复开双岗确认。 */
    @PostMapping("/api/reopen-requests/{reopenRequestId}/confirmations")
    public ResponseEntity<String> confirmReopen(@PathVariable long reopenRequestId,
                                                @Valid @RequestBody ReopenConfirmationRequest request) {
        return respond(controlService.confirmReopen(reopenRequestId, request));
    }

    private ResponseEntity<String> respond(IdempotencyService.Result result) {
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
