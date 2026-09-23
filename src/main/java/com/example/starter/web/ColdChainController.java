package com.example.starter.web;

import com.example.starter.service.ColdChainService;
import com.example.starter.web.dto.EvidenceResponse;
import com.example.starter.web.dto.HandoverRequest;
import com.example.starter.web.dto.HandoverResponse;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.ReadingResponse;
import com.example.starter.web.dto.SegmentEvidence;
import com.example.starter.web.dto.ShipmentCreateRequest;
import com.example.starter.web.dto.ShipmentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 冷链运输追溯 API：建货件、写读数、交接、分段证据查询。
 */
@RestController
@RequestMapping("/api/shipments")
public class ColdChainController {

    private final ColdChainService service;

    public ColdChainController(ColdChainService service) {
        this.service = service;
    }

    /** 建货件。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShipmentResponse create(@Valid @RequestBody ShipmentCreateRequest request) {
        return ShipmentResponse.from(service.createShipment(request));
    }

    /** 写温度读数。 */
    @PostMapping("/{shipmentId}/readings")
    @ResponseStatus(HttpStatus.OK)
    public ReadingResponse addReading(@PathVariable String shipmentId,
                                      @Valid @RequestBody ReadingRequest request) {
        var result = service.addReading(shipmentId, request);
        return ReadingResponse.from(result.reading(), result.replayed());
    }

    /** 交接。 */
    @PostMapping("/{shipmentId}/handovers")
    public ResponseEntity<HandoverResponse> handover(@PathVariable String shipmentId,
                                                     @Valid @RequestBody HandoverRequest request) {
        var outcome = service.handover(shipmentId, request);
        var body = new HandoverResponse(
                shipmentId,
                request.requestId(),
                outcome.closedVersion(),
                outcome.status(),
                outcome.currentVersion(),
                outcome.receiver(),
                outcome.handoverAt(),
                outcome.lastSequence(),
                outcome.replayed()
        );
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    /** 分段证据查询。 */
    @GetMapping("/{shipmentId}/evidence")
    public EvidenceResponse evidence(@PathVariable String shipmentId) {
        var result = service.evidence(shipmentId);
        var segments = result.segments().stream()
                .map(sr -> SegmentEvidence.from(sr.segment(), sr.readings()))
                .toList();
        return new EvidenceResponse(ShipmentResponse.from(result.shipment()), segments);
    }
}
