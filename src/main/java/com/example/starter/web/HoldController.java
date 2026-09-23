package com.example.starter.web;

import com.example.starter.domain.LegalHold;
import com.example.starter.service.HoldService;
import com.example.starter.web.dto.CreateHoldRequest;
import com.example.starter.web.dto.HoldResponse;
import com.example.starter.web.dto.ReleaseHoldRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 法律保全创建、解除与查询 API。
 */
@RestController
@RequestMapping("/api/holds")
public class HoldController {

    private final HoldService holdService;

    public HoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse create(@Valid @RequestBody CreateHoldRequest request) {
        return toResponse(holdService.create(request));
    }

    @PostMapping("/release")
    public HoldResponse release(@Valid @RequestBody ReleaseHoldRequest request) {
        return toResponse(holdService.release(request));
    }

    @GetMapping("/{holdId}")
    public HoldResponse get(@PathVariable long holdId) {
        return toResponse(holdService.get(holdId));
    }

    private HoldResponse toResponse(LegalHold h) {
        return new HoldResponse(h.holdId(), h.caseId(), h.status().name(), h.version(),
                h.evidenceIds(), h.createRequestId(), h.releaseRequestId(),
                h.createdAt(), h.releasedAt());
    }
}
