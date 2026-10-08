package com.example.starter.pest;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 虫情处置链接口。
 */
@RestController
@RequestMapping("/api")
public class PestCaseController {

    private final PestCaseService service;

    public PestCaseController(PestCaseService service) {
        this.service = service;
    }

    /**
     * 虫情上报：POST /api/zones/{zoneCode}/pest-reports
     */
    @PostMapping("/zones/{zoneCode}/pest-reports")
    public PestDtos.CaseResponse report(@PathVariable String zoneCode,
            @Valid @RequestBody PestDtos.ReportRequest request) {
        return service.report(zoneCode, request);
    }

    /**
     * 确认虫情（库区进入处置状态）：POST /api/cases/{caseId}/confirm
     */
    @PostMapping("/cases/{caseId}/confirm")
    public PestDtos.CaseResponse confirm(@PathVariable long caseId,
            @Valid @RequestBody PestDtos.OperatorRequest request) {
        return service.confirm(caseId, request);
    }

    /**
     * 登记新增虫迹：POST /api/cases/{caseId}/traces
     */
    @PostMapping("/cases/{caseId}/traces")
    public PestDtos.TraceResponse addTrace(@PathVariable long caseId,
            @Valid @RequestBody PestDtos.TraceRequest request) {
        return service.addTrace(caseId, request);
    }

    /**
     * 申请解除（恰满 14 天且无新增虫迹）：POST /api/cases/{caseId}/release-application
     */
    @PostMapping("/cases/{caseId}/release-application")
    public PestDtos.CaseResponse applyRelease(@PathVariable long caseId,
            @Valid @RequestBody PestDtos.OperatorRequest request) {
        return service.applyRelease(caseId, request);
    }

    /**
     * 解除确认（消杀与库房双方）：POST /api/cases/{caseId}/release-confirmation
     */
    @PostMapping("/cases/{caseId}/release-confirmation")
    public PestDtos.CaseResponse confirmRelease(@PathVariable long caseId,
            @Valid @RequestBody PestDtos.SideRequest request) {
        return service.confirmRelease(caseId, request);
    }

    /**
     * 处置链详情：GET /api/cases/{caseId}
     */
    @GetMapping("/cases/{caseId}")
    public PestDtos.CaseDetailResponse detail(@PathVariable long caseId) {
        return service.detail(caseId);
    }

    /**
     * 当前观察起点：GET /api/cases/{caseId}/observation
     */
    @GetMapping("/cases/{caseId}/observation")
    public PestDtos.ObservationResponse observation(@PathVariable long caseId) {
        return service.observation(caseId);
    }

    /**
     * 整体预防性消杀评估列表：GET /api/preventive-evaluations
     */
    @GetMapping("/preventive-evaluations")
    public List<PestDtos.EvaluationResponse> listEvaluations() {
        return service.listEvaluations();
    }
}
