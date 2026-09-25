package com.example.starter.fishery.api;

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
import com.example.starter.fishery.service.FisheryService;
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

    public FisheryController(FisheryService service) {
        this.service = service;
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
}
