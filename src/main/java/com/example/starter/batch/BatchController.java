package com.example.starter.batch;

import com.example.starter.api.NotFoundException;
import com.example.starter.event.LateEventRepository;
import com.example.starter.inspection.RapidTestRepository;
import com.example.starter.inspection.RapidTestResponse;
import com.example.starter.inspection.SampleRepository;
import com.example.starter.inspection.SampleResponse;
import com.example.starter.recheck.RecheckRepository;
import com.example.starter.recheck.RetentionRepository;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进场批次接口：产地报备与批次全链路查询。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batchService;
    private final BatchRepository batchRepository;
    private final SampleRepository sampleRepository;
    private final RapidTestRepository rapidTestRepository;
    private final RecheckRepository recheckRepository;
    private final RetentionRepository retentionRepository;
    private final LateEventRepository lateEventRepository;

    public BatchController(BatchService batchService, BatchRepository batchRepository,
                           SampleRepository sampleRepository, RapidTestRepository rapidTestRepository,
                           RecheckRepository recheckRepository, RetentionRepository retentionRepository,
                           LateEventRepository lateEventRepository) {
        this.batchService = batchService;
        this.batchRepository = batchRepository;
        this.sampleRepository = sampleRepository;
        this.rapidTestRepository = rapidTestRepository;
        this.recheckRepository = recheckRepository;
        this.retentionRepository = retentionRepository;
        this.lateEventRepository = lateEventRepository;
    }

    /**
     * 产地报备（幂等）。同键同参返回首次结果，同键异参返回 409。
     */
    @PostMapping
    public BatchResponse register(@Valid @RequestBody BatchCreateRequest request) {
        return batchService.register(request);
    }

    /**
     * 批次全链路详情：抽样链、判定记录、复检、留样与迟到事件。
     */
    @GetMapping("/{batchId}")
    @Transactional(readOnly = true)
    public BatchDetailResponse detail(@PathVariable long batchId) {
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchId));
        return new BatchDetailResponse(
                BatchResponse.from(batch),
                sampleRepository.findByBatchId(batchId).stream().map(SampleResponse::from).toList(),
                rapidTestRepository.findByBatchId(batchId).stream()
                        .map(t -> RapidTestResponse.from(t, batch.status())).toList(),
                recheckRepository.findByBatchId(batchId),
                retentionRepository.findByBatchId(batchId).orElse(null),
                lateEventRepository.findByBatchId(batchId));
    }
}
