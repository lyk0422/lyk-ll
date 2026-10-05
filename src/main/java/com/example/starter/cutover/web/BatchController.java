package com.example.starter.cutover.web;

import com.example.starter.cutover.dto.BatchActionResult;
import com.example.starter.cutover.dto.BatchView;
import com.example.starter.cutover.dto.BlockDetailView;
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
 * 割接批次 REST 接口：发布、依赖拓扑查询、阻断明细查询。
 */
@RestController
@RequestMapping("/api/cutover-batches")
public class BatchController {

    private final BatchService service;

    public BatchController(BatchService service) {
        this.service = service;
    }

    /** 发布批次（batchId 幂等）；首次 201，同键同参重放 200，同键异参 409。 */
    @PostMapping
    public ResponseEntity<BatchActionResult> publish(
            @Valid @RequestBody PublishBatchRequest req) {
        BatchActionResult result = service.publish(req);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result);
    }

    /** 依赖拓扑查询：成员（含冻结版本/窗口快照/阻断来源）与依赖边，按申请编号稳定排序。 */
    @GetMapping("/{batchId}")
    public BatchView get(@PathVariable String batchId) {
        return service.getBatch(batchId);
    }

    /** 阻断明细查询：首个阻断来源集合，按申请编号稳定排序。 */
    @GetMapping("/{batchId}/blocks")
    public List<BlockDetailView> blocks(@PathVariable String batchId) {
        return service.listBlocks(batchId);
    }
}
