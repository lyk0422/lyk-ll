package com.example.starter.coldchain.web;

import com.example.starter.coldchain.dto.CreateShipmentRequest;
import com.example.starter.coldchain.dto.HandoverRequest;
import com.example.starter.coldchain.dto.HandoverResponse;
import com.example.starter.coldchain.dto.ReadingRequest;
import com.example.starter.coldchain.dto.ReadingResponse;
import com.example.starter.coldchain.dto.SegmentEvidenceResponse;
import com.example.starter.coldchain.entity.Shipment;
import com.example.starter.coldchain.service.ColdChainService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 冷链运输追溯 HTTP 接口。
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
    public Shipment create(@Valid @RequestBody CreateShipmentRequest request) {
        return service.createShipment(request);
    }

    /** 查货件。 */
    @GetMapping("/{shipmentId}")
    public Shipment get(@PathVariable String shipmentId) {
        return service.getShipment(shipmentId);
    }

    /** 写温度读数。 */
    @PostMapping("/{shipmentId}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingResponse writeReading(@PathVariable String shipmentId,
                                        @Valid @RequestBody ReadingRequest request) {
        return service.writeReading(shipmentId, request);
    }

    /** 交接。 */
    @PostMapping("/{shipmentId}/handovers")
    @ResponseStatus(HttpStatus.CREATED)
    public HandoverResponse handover(@PathVariable String shipmentId,
                                     @Valid @RequestBody HandoverRequest request) {
        return service.handover(shipmentId, request);
    }

    /** 分段证据查询。 */
    @GetMapping("/{shipmentId}/segments")
    public List<SegmentEvidenceResponse> evidence(@PathVariable String shipmentId) {
        return service.segmentEvidence(shipmentId);
    }
}
