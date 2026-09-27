package com.example.starter.slope;

import com.example.starter.slope.dto.ApiDtos.ArchiveRequest;
import com.example.starter.slope.dto.ApiDtos.ChainDetailResponse;
import com.example.starter.slope.dto.ApiDtos.CreateZoneRequest;
import com.example.starter.slope.dto.ApiDtos.EffectiveThresholdResponse;
import com.example.starter.slope.dto.ApiDtos.EvacuationConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.IngestReadingRequest;
import com.example.starter.slope.dto.ApiDtos.LateEventItem;
import com.example.starter.slope.dto.ApiDtos.PublishStrategyRequest;
import com.example.starter.slope.dto.ApiDtos.RainfallHourRequest;
import com.example.starter.slope.dto.ApiDtos.RateCurveResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionEvidenceItem;
import com.example.starter.slope.dto.ApiDtos.StrategyInfo;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 边坡雷达监测预警与停产撤人 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class SlopeController {

    private final SlopeService slopeService;

    public SlopeController(SlopeService slopeService) {
        this.slopeService = slopeService;
    }

    @PostMapping("/zones")
    public ResponseEntity<String> createZone(@Valid @RequestBody CreateZoneRequest request) {
        return stored(slopeService.createZone(request));
    }

    @PostMapping("/zones/{zoneId}/readings")
    public ResponseEntity<String> ingestReading(@PathVariable long zoneId,
                                                @Valid @RequestBody IngestReadingRequest request) {
        return stored(slopeService.ingestReading(zoneId, request));
    }

    @PostMapping("/chains/{chainId}/evacuation-confirmations")
    public ResponseEntity<String> confirmEvacuation(@PathVariable long chainId,
                                                    @Valid @RequestBody EvacuationConfirmRequest request) {
        return stored(slopeService.confirmEvacuation(chainId, request));
    }

    @PostMapping("/chains/{chainId}/resumption-applications")
    public ResponseEntity<String> applyResumption(@PathVariable long chainId,
                                                  @Valid @RequestBody ResumptionApplyRequest request) {
        return stored(slopeService.applyResumption(chainId, request));
    }

    @PostMapping("/chains/{chainId}/resumption-confirmations")
    public ResponseEntity<String> confirmResumption(@PathVariable long chainId,
                                                    @Valid @RequestBody ResumptionConfirmRequest request) {
        return stored(slopeService.confirmResumption(chainId, request));
    }

    @PostMapping("/chains/{chainId}/archive")
    public ResponseEntity<String> archiveChain(@PathVariable long chainId,
                                               @Valid @RequestBody ArchiveRequest request) {
        return stored(slopeService.archiveChain(chainId, request));
    }

    @GetMapping("/zones/{zoneId}/rate-curve")
    public RateCurveResponse rateCurve(@PathVariable long zoneId) {
        return slopeService.rateCurve(zoneId);
    }

    @GetMapping("/chains/{chainId}")
    public ChainDetailResponse chainDetail(@PathVariable long chainId) {
        return slopeService.chainDetail(chainId);
    }

    @GetMapping("/zones/{zoneId}/late-events")
    public List<LateEventItem> lateEvents(@PathVariable long zoneId) {
        return slopeService.lateEvents(zoneId);
    }

    @PostMapping("/zones/{zoneId}/rainfall-hours")
    public ResponseEntity<String> submitRainfallHour(@PathVariable long zoneId,
                                                     @Valid @RequestBody RainfallHourRequest request) {
        return stored(slopeService.submitRainfallHour(zoneId, request));
    }

    @PostMapping("/zones/{zoneId}/strategies")
    public ResponseEntity<String> publishStrategy(@PathVariable long zoneId,
                                                  @Valid @RequestBody PublishStrategyRequest request) {
        return stored(slopeService.publishStrategy(zoneId, request));
    }

    @GetMapping("/zones/{zoneId}/strategies")
    public List<StrategyInfo> listStrategies(@PathVariable long zoneId) {
        return slopeService.listStrategies(zoneId);
    }

    @GetMapping("/zones/{zoneId}/effective-thresholds")
    public EffectiveThresholdResponse effectiveThresholds(
            @PathVariable long zoneId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        return slopeService.effectiveThresholds(zoneId, at);
    }

    @GetMapping("/chains/{chainId}/resumption-evidence")
    public List<ResumptionEvidenceItem> resumptionEvidence(@PathVariable long chainId) {
        return slopeService.resumptionEvidence(chainId);
    }

    private ResponseEntity<String> stored(IdempotencyService.Stored result) {
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
