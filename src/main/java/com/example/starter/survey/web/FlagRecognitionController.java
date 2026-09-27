package com.example.starter.survey.web;

import com.example.starter.survey.service.FlagReportService;
import com.example.starter.survey.service.FlagRuleService;
import com.example.starter.survey.service.RecognitionService;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.ApplyRecognitionRequest;
import com.example.starter.survey.web.dto.ApplyRecognitionResponse;
import com.example.starter.survey.web.dto.CorrectFlagReportRequest;
import com.example.starter.survey.web.dto.EvidenceChainResponse;
import com.example.starter.survey.web.dto.FlagReportResponse;
import com.example.starter.survey.web.dto.FlagRuleVersionResponse;
import com.example.starter.survey.web.dto.ImportFlagReportRequest;
import com.example.starter.survey.web.dto.RecognitionBindingResponse;
import com.example.starter.survey.web.dto.UpsertFlagRuleRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 船旗国检查证据限定范围互认 REST 入口（合成规则，不访问真实机构）。 */
@RestController
@RequestMapping("/api/flag")
public class FlagRecognitionController {

    private final FlagRuleService ruleService;
    private final FlagReportService reportService;
    private final RecognitionService recognitionService;

    public FlagRecognitionController(FlagRuleService ruleService,
                                     FlagReportService reportService,
                                     RecognitionService recognitionService) {
        this.ruleService = ruleService;
        this.reportService = reportService;
        this.recognitionService = recognitionService;
    }

    // ---- 互认规则版本 ----

    @PostMapping("/rules")
    public ApiResult<FlagRuleVersionResponse> upsertRule(
            @Valid @RequestBody UpsertFlagRuleRequest request) {
        return ruleService.upsert(request);
    }

    @GetMapping("/rules/{flagCode}")
    public FlagRuleVersionResponse currentRule(@PathVariable String flagCode) {
        return ruleService.getCurrent(flagCode);
    }

    @GetMapping("/rules/{flagCode}/versions")
    public List<FlagRuleVersionResponse> ruleVersions(@PathVariable String flagCode) {
        return ruleService.listVersions(flagCode);
    }

    // ---- 外部检查证据报告 ----

    @PostMapping("/reports")
    public ApiResult<FlagReportResponse> importReport(
            @Valid @RequestBody ImportFlagReportRequest request) {
        return reportService.importReport(request);
    }

    @PostMapping("/reports/{reportNo}/corrections")
    public ApiResult<FlagReportResponse> correctReport(
            @PathVariable String reportNo,
            @Valid @RequestBody CorrectFlagReportRequest request) {
        return reportService.correct(reportNo, request);
    }

    @GetMapping("/reports/{reportNo}/revisions")
    public List<FlagReportResponse> reportRevisions(@PathVariable String reportNo) {
        return reportService.listRevisions(reportNo);
    }

    // ---- 互认绑定与证据链 ----

    @PostMapping("/recognitions")
    public ApiResult<ApplyRecognitionResponse> applyRecognition(
            @Valid @RequestBody ApplyRecognitionRequest request) {
        return recognitionService.apply(request);
    }
}
