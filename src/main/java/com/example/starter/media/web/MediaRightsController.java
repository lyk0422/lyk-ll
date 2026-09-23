package com.example.starter.media.web;

import com.example.starter.media.service.MediaRightsService;
import com.example.starter.media.service.ServiceResult;
import com.example.starter.media.web.dto.CorrectGrantRequest;
import com.example.starter.media.web.dto.CreateGrantRequest;
import com.example.starter.media.web.dto.EvidenceItemView;
import com.example.starter.media.web.dto.GrantView;
import com.example.starter.media.web.dto.PlayReceiptRequest;
import com.example.starter.media.web.dto.PlayabilityCheckRequest;
import com.example.starter.media.web.dto.ReceiptView;
import com.example.starter.media.web.dto.RevokeGrantRequest;
import com.example.starter.media.web.dto.ScheduleView;
import com.example.starter.media.web.dto.SubmitScheduleRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 媒体版权窗口 REST API：授权创建/更正/撤销、排期提交、播出回执、可播校验、历史证据查询。
 */
@RestController
@RequestMapping("/api/media")
public class MediaRightsController {

    private final MediaRightsService service;

    public MediaRightsController(MediaRightsService service) {
        this.service = service;
    }

    @PostMapping("/grants")
    public ResponseEntity<GrantView> createGrant(@Valid @RequestBody CreateGrantRequest request) {
        return toEntity(service.createGrant(request));
    }

    @PostMapping("/grants/{grantId}/corrections")
    public ResponseEntity<GrantView> correctGrant(@PathVariable String grantId,
                                                  @Valid @RequestBody CorrectGrantRequest request) {
        return toEntity(service.correctGrant(grantId, request));
    }

    @PostMapping("/grants/{grantId}/revocation")
    public ResponseEntity<GrantView> revokeGrant(@PathVariable String grantId,
                                                 @Valid @RequestBody RevokeGrantRequest request) {
        return toEntity(service.revokeGrant(grantId, request));
    }

    @GetMapping("/grants/{grantId}")
    public GrantView getGrant(@PathVariable String grantId) {
        return service.getGrant(grantId);
    }

    @GetMapping("/grants/{grantId}/history")
    public List<GrantView> getGrantHistory(@PathVariable String grantId) {
        return service.listGrantHistory(grantId);
    }

    @GetMapping("/grants/{grantId}/evidence")
    public List<EvidenceItemView> getGrantEvidence(@PathVariable String grantId) {
        return service.listGrantEvidence(grantId);
    }

    @PostMapping("/schedules")
    public ResponseEntity<ScheduleView> submitSchedule(@Valid @RequestBody SubmitScheduleRequest request) {
        return toEntity(service.submitSchedule(request));
    }

    @GetMapping("/schedules/{scheduleId}")
    public ScheduleView getSchedule(@PathVariable String scheduleId) {
        return service.getSchedule(scheduleId);
    }

    @GetMapping("/schedules/{scheduleId}/evidence")
    public List<EvidenceItemView> getScheduleEvidence(@PathVariable String scheduleId) {
        return service.listScheduleEvidence(scheduleId);
    }

    @PostMapping("/playback/receipts")
    public ResponseEntity<ReceiptView> markPlayed(@Valid @RequestBody PlayReceiptRequest request) {
        return toEntity(service.markPlayed(request));
    }

    @PostMapping("/playback/check")
    public Map<String, Object> playable(@Valid @RequestBody PlayabilityCheckRequest request) {
        return Map.of("playable", service.playable(request));
    }

    private static <T> ResponseEntity<T> toEntity(ServiceResult<T> result) {
        return ResponseEntity.status(result.statusCode()).body(result.body());
    }
}
