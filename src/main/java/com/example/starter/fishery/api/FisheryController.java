package com.example.starter.fishery.api;

import com.example.starter.fishery.api.dto.CarryoverRequest;
import com.example.starter.fishery.api.dto.CarryoverResponse;
import com.example.starter.fishery.api.dto.CarryoverView;
import com.example.starter.fishery.api.dto.DepartureRequest;
import com.example.starter.fishery.api.dto.DepartureResponse;
import com.example.starter.fishery.api.dto.LandingRequest;
import com.example.starter.fishery.api.dto.LandingResponse;
import com.example.starter.fishery.api.dto.LogbookRequest;
import com.example.starter.fishery.api.dto.LogbookResponse;
import com.example.starter.fishery.api.dto.QuotaView;
import com.example.starter.fishery.api.dto.ReconcileResponse;
import com.example.starter.fishery.api.dto.ReviewDecisionRequest;
import com.example.starter.fishery.api.dto.ReviewDecisionResponse;
import com.example.starter.fishery.api.dto.SettlementRequest;
import com.example.starter.fishery.api.dto.SettlementResponse;
import com.example.starter.fishery.api.dto.TransferConfirmRequest;
import com.example.starter.fishery.api.dto.TransferRequest;
import com.example.starter.fishery.api.dto.TransferResponse;
import com.example.starter.fishery.service.CarryoverService;
import com.example.starter.fishery.service.FisheryService;
import com.example.starter.fishery.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 渔业配额与渔捞日志核销 REST 入口。
 */
@RestController
@RequestMapping("/api/fishery")
public class FisheryController {

    private final FisheryService service;
    private final TransferService transferService;
    private final CarryoverService carryoverService;

    public FisheryController(FisheryService service, TransferService transferService,
                             CarryoverService carryoverService) {
        this.service = service;
        this.transferService = transferService;
        this.carryoverService = carryoverService;
    }

    @PostMapping("/departures")
    @ResponseStatus(HttpStatus.CREATED)
    public DepartureResponse departure(@Valid @RequestBody DepartureRequest request) {
        return service.declareDeparture(request);
    }

    @PostMapping("/logbooks")
    @ResponseStatus(HttpStatus.CREATED)
    public LogbookResponse logbook(@Valid @RequestBody LogbookRequest request) {
        return service.submitLogbook(request);
    }

    @PostMapping("/landings")
    @ResponseStatus(HttpStatus.CREATED)
    public LandingResponse landing(@Valid @RequestBody LandingRequest request) {
        return service.reportLanding(request);
    }

    @PostMapping("/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementResponse settlement(@Valid @RequestBody SettlementRequest request) {
        return service.settle(request);
    }

    @PostMapping("/reviews/{reviewId}/decision")
    public ReviewDecisionResponse decision(@PathVariable Long reviewId,
                                           @Valid @RequestBody ReviewDecisionRequest request) {
        return service.decideReview(reviewId, request);
    }

    @GetMapping("/trips/{tripCode}/reconcile")
    public ReconcileResponse reconcile(@PathVariable String tripCode) {
        return service.reconcile(tripCode);
    }

    @GetMapping("/quotas")
    public List<QuotaView> quotas(@RequestParam(required = false) String vesselCode,
                                  @RequestParam(required = false) Integer year) {
        return service.quotas(vesselCode, year);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse createTransfer(@Valid @RequestBody TransferRequest request) {
        return transferService.create(request);
    }

    @PostMapping("/transfers/{transferId}/confirm")
    public TransferResponse confirmTransfer(@PathVariable long transferId,
                                            @Valid @RequestBody TransferConfirmRequest request) {
        return transferService.confirm(transferId, request);
    }

    @GetMapping("/transfers/{transferId}")
    public TransferResponse transfer(@PathVariable long transferId) {
        return transferService.get(transferId);
    }

    @PostMapping("/carryovers")
    @ResponseStatus(HttpStatus.CREATED)
    public CarryoverResponse carryover(@Valid @RequestBody CarryoverRequest request) {
        return carryoverService.carryover(request);
    }

    @GetMapping("/carryovers")
    public List<CarryoverView> carryovers(@RequestParam(required = false) String vesselCode,
                                          @RequestParam(required = false) Integer year) {
        return carryoverService.list(vesselCode, year);
    }
}
