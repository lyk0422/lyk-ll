package com.example.starter.survey.web;

import com.example.starter.survey.data.FlagRuleRepository;
import com.example.starter.survey.service.FlagRecognitionService;
import com.example.starter.survey.service.FlagReportService;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.ApplyRecognitionRequest;
import com.example.starter.survey.web.dto.CorrectReportRequest;
import com.example.starter.survey.web.dto.EvidenceChainResponse;
import com.example.starter.survey.web.dto.FlagRecognitionResponse;
import com.example.starter.survey.web.dto.FlagReportResponse;
import com.example.starter.survey.web.dto.FlagReportRevisionResponse;
import com.example.starter.survey.web.dto.FlagRuleResponse;
import com.example.starter.survey.web.dto.ImportReportRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 船旗国检查证据限定范围互认 REST 入口（合成规则，不访问真实机构）。 */
@RestController
@RequestMapping("/api/flag")
public class FlagController {

    private final FlagReportService reportService;
    private final FlagRecognitionService recognitionService;
    private final FlagRuleRepository ruleRepository;

    public FlagController(FlagReportService reportService,
                          FlagRecognitionService recognitionService,
                          FlagRuleRepository ruleRepository) {
        this.reportService = reportService;
        this.recognitionService = recognitionService;
        this.ruleRepository = ruleRepository;
    }

    // ---- 互认规则版本 ----

    @GetMapping("/rules")
    public List<FlagRuleResponse> listRules() {
        return ruleRepository.findAll().stream()
                .map(r -> FlagRuleResponse.from(r, ruleRepository.findItems(r.id())))
                .toList();
    }

    @GetMapping("/rules/{version}")
    public FlagRuleResponse getRule(@PathVariable int version) {
        return ruleRepository.findByVersion(version)
                .map(r -> FlagRuleResponse.from(r, ruleRepository.findItems(r.id())))
                .orElseThrow(() -> new NotFoundException("RULE_NOT_FOUND",
                        "互认规则版本不存在: " + version));
    }

    // ---- 外部检查报告：导入 / 更正 / 查询 ----

    @PostMapping("/reports")
    public ApiResult<FlagReportResponse> importReport(
            @Valid @RequestBody ImportReportRequest request) {
        return reportService.importReport(request);
    }

    @PostMapping("/reports/correct")
    public ApiResult<FlagReportResponse> correctReport(
            @Valid @RequestBody CorrectReportRequest request) {
        return reportService.correct(request);
    }

    @GetMapping("/reports/{reportNo}")
    public FlagReportResponse getReport(@PathVariable String reportNo) {
        return reportService.getReport(reportNo);
    }

    @GetMapping("/reports/{reportNo}/revisions")
    public List<FlagReportRevisionResponse> listRevisions(@PathVariable String reportNo) {
        return reportService.listRevisions(reportNo);
    }

    // ---- 互认绑定：申请 / 查询 / 证据链 ----

    @PostMapping("/recognitions")
    public ApiResult<FlagRecognitionResponse> applyRecognition(
            @Valid @RequestBody ApplyRecognitionRequest request) {
        return recognitionService.apply(request);
    }

    @GetMapping("/recognitions")
    public List<FlagRecognitionResponse> listRecognitions(@RequestParam long surveyId) {
        return recognitionService.listBySurvey(surveyId);
    }

    @GetMapping("/surveys/{surveyId}/evidence-chain")
    public EvidenceChainResponse evidenceChain(@PathVariable long surveyId) {
        return recognitionService.evidenceChain(surveyId);
    }
}
