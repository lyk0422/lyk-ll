package com.example.starter.survey.web;

import com.example.starter.survey.service.InspectionReportService;
import com.example.starter.survey.service.RecognitionRuleService;
import com.example.starter.survey.service.RecognitionService;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.ApplyRecognitionRequest;
import com.example.starter.survey.web.dto.ApplyRecognitionResponse;
import com.example.starter.survey.web.dto.BindingResponse;
import com.example.starter.survey.web.dto.CorrectReportRequest;
import com.example.starter.survey.web.dto.CreateRuleRequest;
import com.example.starter.survey.web.dto.EvidenceChainResponse;
import com.example.starter.survey.web.dto.ImportReportRequest;
import com.example.starter.survey.web.dto.ReportResponse;
import com.example.starter.survey.web.dto.RuleResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 船旗国检查报告互认 REST 入口（仅合成规则与合成签认，不用于生产）。
 * 互认只替代规则映射项目的完成证明，不替代年度窗口、重大缺陷整改与证书签发门禁。
 */
@RestController
@RequestMapping("/api/recognition")
public class RecognitionController {

    private final RecognitionRuleService ruleService;
    private final InspectionReportService reportService;
    private final RecognitionService recognitionService;

    public RecognitionController(RecognitionRuleService ruleService,
                                 InspectionReportService reportService,
                                 RecognitionService recognitionService) {
        this.ruleService = ruleService;
        this.reportService = reportService;
        this.recognitionService = recognitionService;
    }

    // ---- 互认规则版本 ----

    @PostMapping("/rules")
    public ApiResult<RuleResponse> createRule(@Valid @RequestBody CreateRuleRequest request) {
        return ruleService.create(request);
    }

    @GetMapping("/rules")
    public List<RuleResponse> listRules() {
        return ruleService.list();
    }

    @GetMapping("/rules/{ruleVersion}")
    public RuleResponse getRule(@PathVariable int ruleVersion) {
        return ruleService.getByVersion(ruleVersion);
    }

    // ---- 外部检查报告（证据） ----

    @PostMapping("/reports")
    public ApiResult<ReportResponse> importReport(
            @Valid @RequestBody ImportReportRequest request) {
        return reportService.importReport(request);
    }

    @PostMapping("/reports/correct")
    public ApiResult<ReportResponse> correctReport(
            @Valid @RequestBody CorrectReportRequest request) {
        return reportService.correct(request);
    }

    @GetMapping("/reports/{reportNo}")
    public ReportResponse getActiveReport(@PathVariable String reportNo) {
        return reportService.getActive(reportNo);
    }

    @GetMapping("/reports/{reportNo}/revisions")
    public List<ReportResponse> revisions(@PathVariable String reportNo) {
        return reportService.revisions(reportNo);
    }

    @GetMapping("/reports/{reportNo}/chain")
    public EvidenceChainResponse chain(@PathVariable String reportNo) {
        return reportService.chain(reportNo);
    }

    // ---- 互认申请与绑定查询 ----

    @PostMapping("/apply")
    public ApiResult<ApplyRecognitionResponse> apply(
            @Valid @RequestBody ApplyRecognitionRequest request) {
        return recognitionService.apply(request);
    }

    @GetMapping("/bindings")
    public List<BindingResponse> bindings(@RequestParam long surveyId) {
        return recognitionService.bindingsOfSurvey(surveyId);
    }
}
