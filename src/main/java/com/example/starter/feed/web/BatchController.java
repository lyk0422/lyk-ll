package com.example.starter.feed.web;

import com.example.starter.feed.dto.BatchCreateRequest;
import com.example.starter.feed.dto.BatchStatusResponse;
import com.example.starter.feed.dto.FeedingRecordRequest;
import com.example.starter.feed.dto.FeedingRecordResponse;
import com.example.starter.feed.dto.ProductionRequest;
import com.example.starter.feed.dto.ProductionResponse;
import com.example.starter.feed.dto.SlaughterRequest;
import com.example.starter.feed.dto.SlaughterResponse;
import com.example.starter.feed.service.BatchService;
import com.example.starter.feed.service.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 养殖批次接口：登记、投产、含药饲喂、出栏申请、休药期跟踪。
 */
@RestController
@RequestMapping("/api")
public class BatchController {

    private final BatchService batchService;

    public BatchController(BatchService batchService) {
        this.batchService = batchService;
    }

    /** 登记合成养殖批次。 */
    @PostMapping("/batches")
    public BatchStatusResponse createBatch(@Valid @RequestBody BatchCreateRequest request) {
        return batchService.createBatch(request);
    }

    /** 查询批次休药期跟踪状态（可复算字段）。 */
    @GetMapping("/batches/{batchNo}")
    public BatchStatusResponse getBatch(@PathVariable String batchNo) {
        return batchService.getBatch(batchNo);
    }

    /** 已批准配方投产到批次，含药配方触发休药期跟踪/合并。 */
    @PostMapping("/productions")
    public ProductionResponse produce(@Valid @RequestBody ProductionRequest request) {
        return batchService.produce(request);
    }

    /** 登记含药饲喂事件；出栏后迟到只记事件并告警。 */
    @PostMapping("/feedings")
    public FeedingRecordResponse recordFeeding(@Valid @RequestBody FeedingRecordRequest request) {
        return batchService.recordFeeding(request);
    }

    /** 出栏申请（requestId 幂等）；休药期内 422 并提示最早合法出栏时刻。 */
    @PostMapping("/slaughter-applications")
    public ResponseEntity<SlaughterResponse> slaughter(@Valid @RequestBody SlaughterRequest request) {
        IdempotencyService.Result<SlaughterResponse> result = batchService.applySlaughter(request);
        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }
}
