package com.example.starter.slope;

import com.example.starter.slope.dto.ApiDtos.ArchiveRequest;
import com.example.starter.slope.dto.ApiDtos.ChainDetailResponse;
import com.example.starter.slope.dto.ApiDtos.CreateZoneRequest;
import com.example.starter.slope.dto.ApiDtos.EffectiveThresholdResponse;
import com.example.starter.slope.dto.ApiDtos.EvacuationConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.IngestReadingRequest;
import com.example.starter.slope.dto.ApiDtos.LateEventItem;
import com.example.starter.slope.dto.ApiDtos.PolicyPublishRequest;
import com.example.starter.slope.dto.ApiDtos.PolicyVersionInfo;
import com.example.starter.slope.dto.ApiDtos.RainfallCorrectionInfo;
import com.example.starter.slope.dto.ApiDtos.RainfallHourInfo;
import com.example.starter.slope.dto.ApiDtos.RainfallReportRequest;
import com.example.starter.slope.dto.ApiDtos.RateCurveResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionEvidenceInfo;
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
    public ResponseEntity<String> reportRainfall(@PathVariable long zoneId,
                                                 @Valid @RequestBody RainfallReportRequest request) {
        return stored(slopeService.reportRainfall(zoneId, request));
    }

    @GetMapping("/zones/{zoneId}/rainfall-hours")
    public List<RainfallHourInfo> rainfallHours(
            @PathVariable long zoneId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime to) {
        return slopeService.rainfallHours(zoneId, from, to);
    }

    @GetMapping("/zones/{zoneId}/rainfall-corrections")
    public List<RainfallCorrectionInfo> rainfallCorrections(
            @PathVariable long zoneId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime hour) {
        return slopeService.rainfallCorrections(zoneId, hour);
    }

    @PostMapping("/zones/{zoneId}/threshold-policies")
    public ResponseEntity<String> publishPolicy(@PathVariable long zoneId,
                                                @Valid @RequestBody PolicyPublishRequest request) {
        return stored(slopeService.publishPolicy(zoneId, request));
    }

    @GetMapping("/zones/{zoneId}/threshold-policies")
    public List<PolicyVersionInfo> policyVersions(@PathVariable long zoneId) {
        return slopeService.policyVersions(zoneId);
    }

    @GetMapping("/zones/{zoneId}/effective-threshold")
    public EffectiveThresholdResponse effectiveThreshold(
            @PathVariable long zoneId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        return slopeService.effectiveThreshold(zoneId, at);
    }

    @GetMapping("/chains/{chainId}/resumption-evidence")
    public List<ResumptionEvidenceInfo> resumptionEvidence(@PathVariable long chainId) {
        return slopeService.resumptionEvidence(chainId);
    }

    private ResponseEntity<String> stored(IdempotencyService.Stored result) {
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
