package com.example.starter.museum.api;

import com.example.starter.museum.service.EnvironmentService;
import com.example.starter.museum.service.IdempotentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 展柜微环境监测接口：读数上报、工单 24h 处置确认、挂起恢复双方复核。
 */
@RestController
@RequestMapping("/api/environment")
public class EnvironmentController {

    private final EnvironmentService environmentService;
    private final IdempotentService idempotent;

    public EnvironmentController(EnvironmentService environmentService,
                                 IdempotentService idempotent) {
        this.environmentService = environmentService;
        this.idempotent = idempotent;
    }

    @PostMapping("/readings")
    public ReadingResponse ingest(@Valid @RequestBody ReadingRequest req) {
        return idempotent.execute(req.requestId(), req, ReadingResponse.class,
                () -> environmentService.ingestReading(req.showcaseNo(), req.sampleTime(),
                        req.temperature(), req.humidity()));
    }

    @PostMapping("/work-orders/confirm")
    public WorkOrderResponse confirmWorkOrder(@Valid @RequestBody WorkOrderConfirmRequest req) {
        return idempotent.execute(req.requestId(), req, WorkOrderResponse.class,
                () -> environmentService.confirmWorkOrder(req.orderNo(), req.handler(), req.note()));
    }

    @PostMapping("/resume/review")
    public ContractResponse reviewResume(@Valid @RequestBody ResumeReviewRequest req) {
        return idempotent.execute(req.requestId(), req, ContractResponse.class,
                () -> environmentService.reviewResume(req.contractNo(), req.party()));
    }
}
