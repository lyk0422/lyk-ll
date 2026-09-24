package com.example.starter.ili;

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
 * ILI 缺陷对齐与分级复核 REST 入口。
 */
@RestController
@RequestMapping("/api/alignment-batches")
public class AlignmentController {

    private final AlignmentService service;

    public AlignmentController(AlignmentService service) {
        this.service = service;
    }

    /**
     * 提交对齐批次（幂等）：导入两回合缺陷清单并对齐、分级。
     */
    @PostMapping
    public ResponseEntity<BatchView> submit(@Valid @RequestBody BatchSubmitRequest request) {
        BatchView view = service.submit(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * 查询批次对齐与复核结果。
     */
    @GetMapping("/{batchId}")
    public BatchView get(@PathVariable long batchId) {
        return service.getBatch(batchId);
    }

    /**
     * 对增长/严重缺陷匹配对提交复核结论（确认或降级）。
     */
    @PostMapping("/{batchId}/matches/{matchId}/review")
    public BatchView review(@PathVariable long batchId,
                            @PathVariable long matchId,
                            @Valid @RequestBody ReviewRequest request) {
        return service.review(batchId, matchId, request);
    }

    /**
     * 封板：终态，重复调用幂等成功。
     */
    @PostMapping("/{batchId}/seal")
    public BatchView seal(@PathVariable long batchId) {
        return service.seal(batchId);
    }
}
