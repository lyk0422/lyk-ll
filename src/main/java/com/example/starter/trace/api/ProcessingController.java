package com.example.starter.trace.api;

import com.example.starter.trace.api.TraceDtos.ProcessingBatchRequest;
import com.example.starter.trace.api.TraceDtos.ProcessingBatchResponse;
import com.example.starter.trace.service.ProcessingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 加工批接口。
 */
@RestController
@RequestMapping("/api/processing-batches")
public class ProcessingController {

    private final ProcessingService processingService;

    public ProcessingController(ProcessingService processingService) {
        this.processingService = processingService;
    }

    /**
     * 创建加工批，引用已放行采收批。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProcessingBatchResponse create(@Valid @RequestBody ProcessingBatchRequest request) {
        return processingService.create(request);
    }
}
