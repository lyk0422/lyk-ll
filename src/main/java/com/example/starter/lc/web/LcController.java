package com.example.starter.lc.web;

import com.example.starter.lc.dto.CreateLcRequest;
import com.example.starter.lc.dto.LcResponse;
import com.example.starter.lc.dto.OperationResult;
import com.example.starter.lc.dto.PayRequest;
import com.example.starter.lc.dto.PresentDocumentsRequest;
import com.example.starter.lc.dto.RefuseRequest;
import com.example.starter.lc.dto.SubmissionResponse;
import com.example.starter.lc.dto.WaiveDiscrepanciesRequest;
import com.example.starter.lc.service.LcService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 信用证单证服务 REST 入口。
 */
@RestController
@RequestMapping(path = "/api/lc", produces = MediaType.APPLICATION_JSON_VALUE)
public class LcController {

    private final LcService lcService;

    public LcController(LcService lcService) {
        this.lcService = lcService;
    }

    @PostMapping("/credits")
    public ResponseEntity<?> createLc(@Valid @RequestBody CreateLcRequest request) {
        return toEntity(lcService.createLc(request));
    }

    @GetMapping("/credits/{lcNo}")
    public LcResponse getLc(@PathVariable String lcNo) {
        return lcService.getLc(lcNo);
    }

    @PostMapping("/presentations")
    public ResponseEntity<?> present(@Valid @RequestBody PresentDocumentsRequest request) {
        return toEntity(lcService.present(request));
    }

    @GetMapping("/presentations/{batchNo}")
    public SubmissionResponse getSubmission(@PathVariable String batchNo) {
        return lcService.getSubmission(batchNo);
    }

    @PostMapping("/refusals")
    public ResponseEntity<?> refuse(@Valid @RequestBody RefuseRequest request) {
        return toEntity(lcService.refuse(request));
    }

    @PostMapping("/waivers")
    public ResponseEntity<?> waive(@Valid @RequestBody WaiveDiscrepanciesRequest request) {
        return toEntity(lcService.waive(request));
    }

    @PostMapping("/payments")
    public ResponseEntity<?> pay(@Valid @RequestBody PayRequest request) {
        return toEntity(lcService.pay(request));
    }

    private ResponseEntity<?> toEntity(OperationResult result) {
        if (result instanceof OperationResult.Fresh fresh) {
            return ResponseEntity.status(fresh.httpStatus()).body(fresh.body());
        }
        OperationResult.Replay replay = (OperationResult.Replay) result;
        return ResponseEntity.status(replay.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(replay.json());
    }
}
