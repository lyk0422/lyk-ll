package com.example.starter.web;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.api.Requests.PermitApplyRequest;
import com.example.starter.api.Requests.ReevaluateRequest;
import com.example.starter.api.Requests.ReportSubmitRequest;
import com.example.starter.api.Requests.SegmentRegisterRequest;
import com.example.starter.api.Requests.TreeRegisterRequest;
import com.example.starter.api.Requests.WarningCancelRequest;
import com.example.starter.api.Requests.WarningPublishRequest;
import com.example.starter.api.Responses.LedgerEntry;
import com.example.starter.service.LedgerService;
import com.example.starter.service.PermitService;
import com.example.starter.service.SegmentService;
import com.example.starter.service.ServiceResult;
import com.example.starter.service.TreeService;
import com.example.starter.service.WarningService;

/**
 * 行道树风险修剪审批流程接口。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final SegmentService segmentService;
    private final TreeService treeService;
    private final PermitService permitService;
    private final WarningService warningService;
    private final LedgerService ledgerService;

    public ApiController(SegmentService segmentService, TreeService treeService,
                         PermitService permitService, WarningService warningService,
                         LedgerService ledgerService) {
        this.segmentService = segmentService;
        this.treeService = treeService;
        this.permitService = permitService;
        this.warningService = warningService;
        this.ledgerService = ledgerService;
    }

    @PostMapping("/segments")
    public ResponseEntity<Object> registerSegment(@Valid @RequestBody SegmentRegisterRequest request) {
        return toEntity(segmentService.register(request));
    }

    @PostMapping("/trees")
    public ResponseEntity<Object> registerTree(@Valid @RequestBody TreeRegisterRequest request) {
        return toEntity(treeService.register(request));
    }

    @PostMapping("/trees/reevaluations")
    public ResponseEntity<Object> reevaluate(@Valid @RequestBody ReevaluateRequest request) {
        return toEntity(treeService.reevaluate(request));
    }

    @PostMapping("/permits")
    public ResponseEntity<Object> applyPermit(@Valid @RequestBody PermitApplyRequest request) {
        return toEntity(permitService.apply(request));
    }

    @PostMapping("/reports")
    public ResponseEntity<Object> submitReport(@Valid @RequestBody ReportSubmitRequest request) {
        return toEntity(permitService.submitReport(request));
    }

    @PostMapping("/warnings")
    public ResponseEntity<Object> publishWarning(@Valid @RequestBody WarningPublishRequest request) {
        return toEntity(warningService.publish(request));
    }

    @PostMapping("/warnings/cancellations")
    public ResponseEntity<Object> cancelWarning(@Valid @RequestBody WarningCancelRequest request) {
        return toEntity(warningService.cancel(request));
    }

    @GetMapping("/ledger")
    public List<LedgerEntry> ledger() {
        return ledgerService.ledger();
    }

    private static ResponseEntity<Object> toEntity(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
