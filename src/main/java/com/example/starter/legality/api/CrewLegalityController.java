package com.example.starter.legality.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机组值勤合规 HTTP 入口。
 */
@RestController
@RequestMapping("/api/crew-legality")
public class CrewLegalityController {

    private final com.example.starter.legality.service.CrewLegalityService service;

    public CrewLegalityController(com.example.starter.legality.service.CrewLegalityService service) {
        this.service = service;
    }

    /** 单条值勤报到。 */
    @PostMapping("/duties")
    public ReportResponse report(@Valid @RequestBody ReportRequest request) {
        return service.report(request);
    }

    /** 整组排班校验。 */
    @PostMapping("/batches/check")
    public BatchCheckResponse checkBatch(@Valid @RequestBody BatchCheckRequest request) {
        return service.checkBatch(request);
    }

    /** 补交延误延长。 */
    @PostMapping("/delays")
    public DelayResponse submitDelay(@Valid @RequestBody DelayRequest request) {
        return service.submitDelay(request);
    }

    /** 批次封板。 */
    @PostMapping("/batches/{batchId}/finalize")
    public FinalizeResponse finalize(@PathVariable String batchId) {
        return service.finalizeBatch(batchId);
    }
}
