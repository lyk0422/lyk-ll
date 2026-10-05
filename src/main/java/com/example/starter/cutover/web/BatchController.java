package com.example.starter.cutover.web;

import com.example.starter.cutover.dto.BatchActionResult;
import com.example.starter.cutover.dto.BatchView;
import com.example.starter.cutover.dto.BlockDetailView;
import com.example.starter.cutover.dto.CloseBatchRequest;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.service.BatchService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 割接批次 REST 接口：发布、拓扑查询、阻断明细、结束批次。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService service;

    public BatchController(BatchService service) {
        this.service = service;
    }

    /** 发布批次（requestId 幂等）；首次 201，同键同参重放 200。 */
    @PostMapping
    public ResponseEntity<BatchActionResult> publish(@Valid @RequestBody PublishBatchRequest req) {
        BatchActionResult result = service.publish(req);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result);
    }

    /** 批次拓扑查询：成员按申请编号稳定升序，含首个阻断来源集合。 */
    @GetMapping("/{batchId}")
    public BatchView get(@PathVariable String batchId) {
        return service.get(batchId);
    }

    /** 阻断明细：被依赖阻断的成员及其首个阻断来源集合。 */
    @GetMapping("/{batchId}/blocks")
    public List<BlockDetailView> blocks(@PathVariable String batchId) {
        return service.blocks(batchId);
    }

    /** 结束批次（expectedVersion 乐观锁；全部成员完结或阻断后才可结束）。 */
    @PostMapping("/close")
    public BatchActionResult close(@Valid @RequestBody CloseBatchRequest req) {
        return service.close(req);
    }
}
