package com.example.starter.lot.api;

import com.example.starter.lot.service.LotGenealogyService;
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
 * 制造批次谱系 HTTP 入口（骨架，端点已就位）。
 */
@RestController
@RequestMapping("/api/lots")
public class LotController {

    private final LotGenealogyService service;

    public LotController(LotGenealogyService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LotResponse create(@Valid @RequestBody CreateLotRequest request) {
        return service.createLot(request);
    }

    @GetMapping("/{lotId}")
    public LotResponse get(@PathVariable String lotId) {
        return service.getLot(lotId);
    }

    @PostMapping("/conversions")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversionResponse convert(@Valid @RequestBody ConversionRequest request) {
        return service.convert(request);
    }

    @PostMapping("/quarantine")
    public QuarantineResponse quarantine(@Valid @RequestBody QuarantineRequest request) {
        return service.quarantine(request);
    }

    @GetMapping("/{lotId}/genealogy")
    public GenealogyResponse genealogy(@PathVariable String lotId) {
        return service.genealogy(lotId);
    }

    @GetMapping("/quarantine/{sourceLotId}/impact")
    public ImpactResponse impact(@PathVariable String sourceLotId) {
        return service.impact(sourceLotId);
    }
}
