package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.BatchDetailResponse;
import com.example.starter.pesticide.dto.ApiDtos.ReleaseResponse;
import com.example.starter.pesticide.dto.ApiDtos.ReportRequest;
import com.example.starter.pesticide.dto.ApiDtos.ReportResponse;
import com.example.starter.pesticide.dto.ApiDtos.SampleRequest;
import com.example.starter.pesticide.dto.ApiDtos.SampleResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次接口：产地报备、抽样、判定放行与批次详情。
 */
@RestController
@RequestMapping("/api")
public class BatchController {

    private final BatchService batchService;

    public BatchController(BatchService batchService) {
        this.batchService = batchService;
    }

    /**
     * 产地报备（requestId 幂等）。
     */
    @PostMapping("/reports")
    public ResponseEntity<ReportResponse> report(@Valid @RequestBody ReportRequest request) {
        ReportResponse response = batchService.report(request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /**
     * 抽样（批次+项目去重）。
     */
    @PostMapping("/batches/{batchId}/samples")
    public ResponseEntity<SampleResponse> sample(@PathVariable long batchId,
                                                 @Valid @RequestBody SampleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(batchService.sample(batchId, request.item()));
    }

    /**
     * 判定与准入放行（同一事务）。
     */
    @PostMapping("/batches/{batchId}/release")
    public ReleaseResponse release(@PathVariable long batchId) {
        return batchService.release(batchId);
    }

    /**
     * 批次详情。
     */
    @GetMapping("/batches/{batchId}")
    public BatchDetailResponse detail(@PathVariable long batchId) {
        return batchService.detail(batchId);
    }
}
