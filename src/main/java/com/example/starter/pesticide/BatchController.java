package com.example.starter.pesticide;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 药剂批号与召回接口。
 */
@RestController
@RequestMapping("/api")
public class BatchController {

    private final BatchService service;

    public BatchController(BatchService service) {
        this.service = service;
    }

    /**
     * 药剂批号列表：GET /api/pesticides/{pesticideId}/batches
     */
    @GetMapping("/pesticides/{pesticideId}/batches")
    public List<BatchDtos.BatchResponse> listByPesticide(@PathVariable long pesticideId) {
        return service.listByPesticide(pesticideId);
    }

    /**
     * 批号召回（一经生效不得撤回）：POST /api/batches/{batchId}/recall
     */
    @PostMapping("/batches/{batchId}/recall")
    public BatchDtos.RecallResponse recall(@PathVariable long batchId,
            @Valid @RequestBody BatchDtos.RecallRequest request) {
        return service.recall(batchId, request);
    }

    /**
     * 召回详情（含全部关联处置链影响）：GET /api/recalls/{recallId}
     */
    @GetMapping("/recalls/{recallId}")
    public BatchDtos.RecallResponse recallDetail(@PathVariable long recallId) {
        return service.recallDetail(recallId);
    }
}
