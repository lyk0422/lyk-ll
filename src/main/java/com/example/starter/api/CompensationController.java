package com.example.starter.api;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.api.dto.ApproveCompensationRequest;
import com.example.starter.api.dto.FeedRequest;
import com.example.starter.api.dto.RegisterBatchRequest;
import com.example.starter.api.dto.ReviseFactorRequest;
import com.example.starter.service.CompensationService;
import com.example.starter.service.ServiceResult;

/**
 * 染料批次效价补偿与受控投料 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class CompensationController {

    private final CompensationService compensationService;

    public CompensationController(CompensationService compensationService) {
        this.compensationService = compensationService;
    }

    /** 登记染料批次：效价系数、可用克重、有效截止（requestId 幂等）。 */
    @PostMapping("/dye-batches")
    public ResponseEntity<Map<String, Object>> registerBatch(@Valid @RequestBody RegisterBatchRequest req) {
        return toResponse(compensationService.registerBatch(req));
    }

    /** 修订批次系数：expectedVersion 乐观校验，成功版本加 1，未投料批准失效。 */
    @PostMapping("/dye-batches/{batchNo}/factor")
    public ResponseEntity<Map<String, Object>> reviseFactor(@PathVariable String batchNo,
                                                            @Valid @RequestBody ReviseFactorRequest req) {
        return toResponse(compensationService.reviseFactor(batchNo, req));
    }

    /** 批次查询：当前系数版本与可用克重。 */
    @GetMapping("/dye-batches/{batchNo}")
    public ResponseEntity<Map<String, Object>> getBatch(@PathVariable String batchNo) {
        return toResponse(compensationService.getBatch(batchNo));
    }

    /** 补偿批准：为受控缸次每种染料冻结批次、系数与补偿目标（requestId 幂等）。 */
    @PostMapping("/lots/{lotNo}/compensation")
    public ResponseEntity<Map<String, Object>> approveCompensation(
            @PathVariable String lotNo, @Valid @RequestBody ApproveCompensationRequest req) {
        return toResponse(compensationService.approveCompensation(lotNo, req));
    }

    /** 补偿审批与称量快照证据查询。 */
    @GetMapping("/lots/{lotNo}/compensation")
    public ResponseEntity<Map<String, Object>> getCompensationEvidence(@PathVariable String lotNo) {
        return toResponse(compensationService.getCompensationEvidence(lotNo));
    }

    /** 受控投料：验证补偿版本、称量偏差与批次有效期，原子扣减批次余量（requestId 幂等）。 */
    @PostMapping("/controlled-feedings")
    public ResponseEntity<Map<String, Object>> controlledFeed(@Valid @RequestBody FeedRequest req) {
        return toResponse(compensationService.controlledFeed(req));
    }

    private ResponseEntity<Map<String, Object>> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status())
                .header("X-Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.body());
    }
}
