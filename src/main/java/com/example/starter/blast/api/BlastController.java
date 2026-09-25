package com.example.starter.blast.api;

import com.example.starter.blast.service.BlastService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 爆破作业审批与振动监测联动接口。
 */
@RestController
@RequestMapping("/api/blast")
public class BlastController {

    private final BlastService blastService;

    public BlastController(BlastService blastService) {
        this.blastService = blastService;
    }

    /** 提交爆破作业申请（requestId 幂等；首次拒绝结果也按同键重放）。 */
    @PostMapping("/applications")
    public ResponseEntity<Object> apply(@Valid @RequestBody ApplyRequest request) {
        return toEntity(blastService.apply(request));
    }

    /** 审批作业：批准或驳回。 */
    @PostMapping("/applications/{id}/approval")
    public ApplicationResponse approve(@PathVariable long id, @Valid @RequestBody ApprovalRequest request) {
        return blastService.approve(id, request);
    }

    /** 查询作业详情。 */
    @GetMapping("/applications/{id}")
    public ApplicationResponse getApplication(@PathVariable long id) {
        return blastService.getApplication(id);
    }

    /** 起爆备案（requestId 幂等，未审批一律拒绝）。 */
    @PostMapping("/filings")
    public ResponseEntity<Object> filing(@Valid @RequestBody FilingRequest request) {
        return toEntity(blastService.filing(request));
    }

    /** 振动监测读数回传（爆区＋测点＋采样时刻去重，超限触发冻结）。 */
    @PostMapping("/readings")
    public ReadingResult submitReading(@Valid @RequestBody VibrationReadingRequest request) {
        return blastService.submitReading(request);
    }

    /** 监管复核确认解除冻结（requestId 幂等）。 */
    @PostMapping("/mines/{mineCode}/unfreeze")
    public ResponseEntity<Object> unfreeze(@PathVariable String mineCode,
                                           @Valid @RequestBody UnfreezeReviewRequest request) {
        return toEntity(blastService.unfreeze(mineCode, request));
    }

    /** 查询矿区冻结状态。 */
    @GetMapping("/mines/{mineCode}")
    public MineResponse getMine(@PathVariable String mineCode) {
        return blastService.getMine(mineCode);
    }

    /** 效果评估封档（回传齐全后可提交，终态重复封档幂等成功）。 */
    @PostMapping("/evaluations")
    public ResponseEntity<Object> evaluate(@Valid @RequestBody EvaluationRequest request) {
        return toEntity(blastService.evaluate(request));
    }

    private ResponseEntity<Object> toEntity(ServiceResult<Object> result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
