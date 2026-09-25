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
 * 缺陷对齐批次接口：导入对齐、查询、复核、封板。
 */
@RestController
@RequestMapping("/api/alignment-batches")
public class AlignmentController {

    private final AlignmentService alignmentService;

    public AlignmentController(AlignmentService alignmentService) {
        this.alignmentService = alignmentService;
    }

    /** 导入双回合缺陷清单并对齐分级；同 requestId 重放返回首次结果。 */
    @PostMapping
    public ResponseEntity<Dtos.AlignmentBatchResponse> importBatch(
            @Valid @RequestBody Dtos.ImportAlignmentRequest request) {
        AlignmentService.ImportResult result = alignmentService.importAndAlign(request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping("/{batchId}")
    public Dtos.AlignmentBatchResponse getBatch(@PathVariable long batchId) {
        return alignmentService.getBatch(batchId);
    }

    /** 提交单条匹配对的复核结论（确认或降级）。 */
    @PostMapping("/{batchId}/reviews")
    public Dtos.ReviewResponse review(@PathVariable long batchId,
            @Valid @RequestBody Dtos.ReviewRequest request) {
        return alignmentService.review(batchId, request);
    }

    /** 封板：终态；重复封板返回幂等成功。 */
    @PostMapping("/{batchId}/seal")
    public Dtos.SealResponse seal(@PathVariable long batchId) {
        return alignmentService.seal(batchId);
    }
}
