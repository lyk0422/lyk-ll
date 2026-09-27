package com.example.starter.cutover.web;

import com.example.starter.cutover.dto.BatchActionResult;
import com.example.starter.cutover.dto.BatchView;
import com.example.starter.cutover.dto.BlockView;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.dto.TopologyView;
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
 * 割接批次与前置依赖门禁 REST 接口：批次发布、批次/拓扑/阻断明细查询。
 */
@RestController
@RequestMapping("/api/cutover-batches")
public class BatchController {

    private final BatchService service;

    public BatchController(BatchService service) {
        this.service = service;
    }

    /** 发布批次（requestId 幂等）：原子冻结成员、依赖与窗口快照并绑定成员。首次 201，重放 200。 */
    @PostMapping
    public ResponseEntity<BatchActionResult> publish(@Valid @RequestBody PublishBatchRequest req) {
        BatchActionResult result = service.publish(req);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result);
    }

    /** 批次详情：冻结快照、成员实时状态与阻断明细。 */
    @GetMapping("/{batchId}")
    public BatchView get(@PathVariable String batchId) {
        return service.get(batchId);
    }

    /** 拓扑查询：按申请编号稳定排序的节点/边与执行门禁状态。 */
    @GetMapping("/{batchId}/topology")
    public TopologyView topology(@PathVariable String batchId) {
        return service.topology(batchId);
    }

    /** 阻断明细：被阻断申请及其首个阻断来源集合。 */
    @GetMapping("/{batchId}/blocks")
    public List<BlockView> blocks(@PathVariable String batchId) {
        return service.blocks(batchId);
    }
}
