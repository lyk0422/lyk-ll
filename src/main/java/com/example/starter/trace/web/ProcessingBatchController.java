package com.example.starter.trace.web;

import com.example.starter.trace.dto.CreateProcessingBatchRequest;
import com.example.starter.trace.dto.ProcessingBatchResponse;
import com.example.starter.trace.service.ProcessingBatchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 加工批次接口。
 */
@RestController
@RequestMapping("/api/processing-batches")
public class ProcessingBatchController {

    private final ProcessingBatchService processingBatchService;

    public ProcessingBatchController(ProcessingBatchService processingBatchService) {
        this.processingBatchService = processingBatchService;
    }

    /**
     * 创建加工批次，引用已放行采收批。
     */
    @PostMapping
    public ProcessingBatchResponse createProcessingBatch(
            @Valid @RequestBody CreateProcessingBatchRequest request) {
        return processingBatchService.createProcessingBatch(request);
    }
}
