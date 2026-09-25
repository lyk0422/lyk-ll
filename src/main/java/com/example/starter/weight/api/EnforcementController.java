package com.example.starter.weight.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.weight.api.dto.CaseResponse;
import com.example.starter.weight.api.dto.CloseCaseRequest;
import com.example.starter.weight.api.dto.DecisionRequest;
import com.example.starter.weight.api.dto.DecisionResponse;
import com.example.starter.weight.api.dto.ReviewApplyRequest;
import com.example.starter.weight.api.dto.ReviewConcludeRequest;
import com.example.starter.weight.api.dto.ReviewResponse;
import com.example.starter.weight.api.dto.WeighRequest;
import com.example.starter.weight.api.dto.WeighResponse;
import com.example.starter.weight.service.EnforcementService;

import jakarta.validation.Valid;

/**
 * 治超执法 REST 接口：过磅、处罚决定、复检申诉与结论、结案、案件文书链查询。
 */
@RestController
@RequestMapping("/api/enforcement")
public class EnforcementController {

    private final EnforcementService enforcementService;

    public EnforcementController(EnforcementService enforcementService) {
        this.enforcementService = enforcementService;
    }

    /**
     * 车辆过磅上报。
     */
    @PostMapping("/weighs")
    public WeighResponse submitWeigh(@Valid @RequestBody WeighRequest request) {
        return enforcementService.submitWeigh(request);
    }

    /**
     * 依据在案权威过磅记录生成处罚决定（幂等）。
     */
    @PostMapping("/decisions")
    public DecisionResponse issueDecision(@Valid @RequestBody DecisionRequest request) {
        return enforcementService.issueDecision(request);
    }

    /**
     * 当事人在送达后 7 个自然日内申请复检（幂等，同一案件唯一受理）。
     */
    @PostMapping("/reviews/applications")
    public ReviewResponse applyReview(@Valid @RequestBody ReviewApplyRequest request) {
        return enforcementService.applyReview(request);
    }

    /**
     * 复检结论；推翻时同事务撤销原处罚并生成撤销文书。
     */
    @PostMapping("/reviews/conclusions")
    public ReviewResponse concludeReview(@Valid @RequestBody ReviewConcludeRequest request) {
        return enforcementService.concludeReview(request);
    }

    /**
     * 案件结案（终态，重复结案幂等成功）。
     */
    @PostMapping("/cases/close")
    public ResponseEntity<CaseResponse> closeCase(@Valid @RequestBody CloseCaseRequest request) {
        CaseResponse response = enforcementService.closeCase(request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }

    /**
     * 查询案件与文书链。
     */
    @GetMapping("/cases/{caseNo}")
    public CaseResponse getCase(@PathVariable String caseNo) {
        return enforcementService.getCase(caseNo);
    }
}
