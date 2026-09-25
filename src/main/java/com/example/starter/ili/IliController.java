package com.example.starter.ili;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.ili.dto.BatchResultResponse;
import com.example.starter.ili.dto.PairView;
import com.example.starter.ili.dto.ReviewRequest;
import com.example.starter.ili.dto.SealResponse;
import com.example.starter.ili.dto.SubmitBatchRequest;

import jakarta.validation.Valid;

/**
 * ILI 缺陷对齐与分级复核 REST 入口。
 */
@RestController
@RequestMapping("/api/ili/batches")
public class IliController {

    private final IliBatchService service;

    public IliController(IliBatchService service) {
        this.service = service;
    }

    /**
     * 提交双回合缺陷清单并对齐分级；按 requestId 幂等。
     */
    @PostMapping
    public BatchResultResponse submit(@Valid @RequestBody SubmitBatchRequest request) {
        return service.submit(request);
    }

    /**
     * 查询批次对齐结果。
     */
    @GetMapping("/{batchId}")
    public BatchResultResponse get(@PathVariable long batchId) {
        return service.getBatch(batchId);
    }

    /**
     * 对增长、严重缺陷逐条提交复核结论。
     */
    @PostMapping("/{batchId}/reviews")
    public PairView review(@PathVariable long batchId, @Valid @RequestBody ReviewRequest request) {
        return service.review(batchId, request);
    }

    /**
     * 封板：终态，重复调用返回幂等成功。
     */
    @PostMapping("/{batchId}/seal")
    public SealResponse seal(@PathVariable long batchId) {
        return service.seal(batchId);
    }
}
