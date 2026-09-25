package com.example.starter.damage.api;

import com.example.starter.damage.dto.Requests.ApprovalRequest;
import com.example.starter.damage.dto.Requests.CloseRequest;
import com.example.starter.damage.dto.Requests.CompletionRequest;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.PhotoRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.ReinspectionRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.Views.ApprovalView;
import com.example.starter.damage.dto.Views.CaseDetailView;
import com.example.starter.damage.dto.Views.CaseView;
import com.example.starter.damage.dto.Views.CompletionView;
import com.example.starter.damage.dto.Views.EstimateView;
import com.example.starter.damage.dto.Views.PhotoResult;
import com.example.starter.damage.service.DamageCaseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集装箱残损检验与修理放行 API。
 * 流转：到港登记 → 检验 → 估价 → 修理批复 → 复检 → 放行/全损。
 */
@RestController
@RequestMapping("/api/cases")
public class DamageCaseController {

    private final DamageCaseService service;

    public DamageCaseController(DamageCaseService service) {
        this.service = service;
    }

    /** 到港登记 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CaseView register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request);
    }

    /** 检验：按合成 IICL 代码登记残损项 */
    @PostMapping("/{caseId}/survey")
    public CaseDetailView survey(@PathVariable long caseId, @Valid @RequestBody SurveyRequest request) {
        return service.survey(caseId, request);
    }

    /** 估价：生成新估价版本 */
    @PostMapping("/{caseId}/estimates")
    public EstimateView estimate(@PathVariable long caseId, @Valid @RequestBody EstimateRequest request) {
        return service.estimate(caseId, request);
    }

    /** 修理批复 */
    @PostMapping("/{caseId}/approvals")
    public ApprovalView approve(@PathVariable long caseId, @Valid @RequestBody ApprovalRequest request) {
        return service.approve(caseId, request);
    }

    /** 修理（或返修）完工 */
    @PostMapping("/{caseId}/repair-completions")
    public CompletionView completeRepair(@PathVariable long caseId, @Valid @RequestBody CompletionRequest request) {
        return service.completeRepair(caseId, request);
    }

    /** 复检 */
    @PostMapping("/{caseId}/reinspections")
    public CaseView reinspect(@PathVariable long caseId, @Valid @RequestBody ReinspectionRequest request) {
        return service.reinspect(caseId, request);
    }

    /** 结案：修理放行/全损，终态 */
    @PostMapping("/{caseId}/close")
    public CaseView close(@PathVariable long caseId, @Valid @RequestBody CloseRequest request) {
        return service.close(caseId, request);
    }

    /** 检验照片登记；结案后迟到照片只记迟到事件 */
    @PostMapping("/{caseId}/photos")
    public PhotoResult photo(@PathVariable long caseId, @Valid @RequestBody PhotoRequest request) {
        return service.photo(caseId, request);
    }

    /** 案件全量明细：残损清单、估价版本与修理链 */
    @GetMapping("/{caseId}")
    public CaseDetailView detail(@PathVariable long caseId) {
        return service.detail(caseId);
    }
}
