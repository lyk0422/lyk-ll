package com.example.starter.cutover.web;

import com.example.starter.cutover.dto.BatchResult;
import com.example.starter.cutover.dto.BatchView;
import com.example.starter.cutover.dto.BlockedMemberView;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.service.CutoverBatchService;
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
 * 跨设备割接依赖批次 REST 接口：发布冻结、拓扑查询、阻断明细。
 */
@RestController
@RequestMapping("/api/cutover-batches")
public class CutoverBatchController {

    private final CutoverBatchService service;

    public CutoverBatchController(CutoverBatchService service) {
        this.service = service;
    }

    /** 发布依赖批次（requestId 幂等）；首次 201，同键同参重放 200，异参 409。 */
    @PostMapping
    public ResponseEntity<BatchResult> publish(@Valid @RequestBody PublishBatchRequest req) {
        BatchResult result = service.publish(req);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result);
    }

    /** 批次拓扑查询：成员按申请编号稳定升序，含冻结窗口快照与当前状态。 */
    @GetMapping("/{batchId}")
    public BatchView get(@PathVariable String batchId) {
        return service.getBatch(batchId);
    }

    /** 阻断明细：依赖阻断成员及首个阻断来源集合。 */
    @GetMapping("/{batchId}/blocks")
    public List<BlockedMemberView> blocks(@PathVariable String batchId) {
        return service.getBlocks(batchId);
    }
}
