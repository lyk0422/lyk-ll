package com.example.starter.evidence.api;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.evidence.api.dto.ConfirmPurgeRequest;
import com.example.starter.evidence.api.dto.MarkPurgeRequest;
import com.example.starter.evidence.api.dto.PurgeBatchView;
import com.example.starter.evidence.service.PurgeService;

/**
 * 两阶段清除（标记/确认）与清单查询 API。
 */
@RestController
@RequestMapping("/api/purge-batches")
public class PurgeController {

    private final PurgeService purgeService;

    public PurgeController(PurgeService purgeService) {
        this.purgeService = purgeService;
    }

    /**
     * 标记阶段：原子写入 MARKED 批次与候选清单；同幂等键同参数重放返回 200。
     */
    @PostMapping
    public ResponseEntity<PurgeBatchView> mark(@Valid @RequestBody MarkPurgeRequest request) {
        PurgeBatchView view = purgeService.mark(request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /**
     * 最终确认：复核候选并冻结不可变清除清单；重复确认返回首次冻结的清单。
     */
    @PostMapping("/{batchId}/confirm")
    public PurgeBatchView confirm(@PathVariable String batchId,
            @Valid @RequestBody ConfirmPurgeRequest request) {
        return purgeService.confirm(batchId, request);
    }

    /**
     * 查询清除批次、候选清单及已冻结的清除清单。
     */
    @GetMapping("/{batchId}")
    public PurgeBatchView get(@PathVariable String batchId) {
        return purgeService.get(batchId);
    }
}
