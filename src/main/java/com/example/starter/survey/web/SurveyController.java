package com.example.starter.survey.web;

import com.example.starter.survey.data.SurveyEventRepository;
import com.example.starter.survey.domain.SurveyEvent;
import com.example.starter.survey.domain.SurveyType;
import com.example.starter.survey.service.CertificateQueryService;
import com.example.starter.survey.service.CertificateService;
import com.example.starter.survey.service.DefectService;
import com.example.starter.survey.service.ExtensionService;
import com.example.starter.survey.service.RecognitionService;
import com.example.starter.survey.service.ShipService;
import com.example.starter.survey.service.SurveyService;
import com.example.starter.survey.service.WindowLedgerService;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.ApplyExtensionRequest;
import com.example.starter.survey.web.dto.ApplySurveyRequest;
import com.example.starter.survey.web.dto.ApproveExtensionRequest;
import com.example.starter.survey.web.dto.BindRecognitionRequest;
import com.example.starter.survey.web.dto.BindingResponse;
import com.example.starter.survey.web.dto.BoardResponse;
import com.example.starter.survey.web.dto.BoardSurveyRequest;
import com.example.starter.survey.web.dto.CertificateResponse;
import com.example.starter.survey.web.dto.CertifyRequest;
import com.example.starter.survey.web.dto.CertifyResponse;
import com.example.starter.survey.web.dto.CreateShipRequest;
import com.example.starter.survey.web.dto.DefectResponse;
import com.example.starter.survey.web.dto.EvidenceChainResponse;
import com.example.starter.survey.web.dto.ExtensionResponse;
import com.example.starter.survey.web.dto.ImportReportRequest;
import com.example.starter.survey.web.dto.PublishRuleRequest;
import com.example.starter.survey.web.dto.RectifyDefectRequest;
import com.example.starter.survey.web.dto.RectifyResponse;
import com.example.starter.survey.web.dto.ReportResponse;
import com.example.starter.survey.web.dto.RuleVersionResponse;
import com.example.starter.survey.web.dto.ShipResponse;
import com.example.starter.survey.web.dto.SurveyResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 船级检验周期与展期审批服务 REST 入口。 */
@RestController
@RequestMapping("/api")
public class SurveyController {

    private final ShipService shipService;
    private final SurveyService surveyService;
    private final DefectService defectService;
    private final CertificateService certificateService;
    private final CertificateQueryService certificateQueryService;
    private final ExtensionService extensionService;
    private final WindowLedgerService ledgerService;
    private final RecognitionService recognitionService;
    private final SurveyEventRepository eventRepository;

    public SurveyController(ShipService shipService,
                            SurveyService surveyService,
                            DefectService defectService,
                            CertificateService certificateService,
                            CertificateQueryService certificateQueryService,
                            ExtensionService extensionService,
                            WindowLedgerService ledgerService,
                            RecognitionService recognitionService,
                            SurveyEventRepository eventRepository) {
        this.shipService = shipService;
        this.surveyService = surveyService;
        this.defectService = defectService;
        this.certificateService = certificateService;
        this.certificateQueryService = certificateQueryService;
        this.extensionService = extensionService;
        this.ledgerService = ledgerService;
        this.recognitionService = recognitionService;
        this.eventRepository = eventRepository;
    }

    // ---- 船舶 ----

    @PostMapping("/ships")
    public ApiResult<ShipResponse> registerShip(@Valid @RequestBody CreateShipRequest request) {
        return shipService.register(request);
    }

    @GetMapping("/ships")
    public List<ShipResponse> listShips() {
        return shipService.list();
    }

    // ---- 检验：申请 / 登轮 ----

    @PostMapping("/surveys")
    public ApiResult<SurveyResponse> applySurvey(@Valid @RequestBody ApplySurveyRequest request) {
        return surveyService.apply(request);
    }

    @PostMapping("/surveys/board")
    public ApiResult<BoardResponse> boardSurvey(@Valid @RequestBody BoardSurveyRequest request) {
        return surveyService.board(request);
    }

    @GetMapping("/surveys/{id}")
    public SurveyResponse getSurvey(@PathVariable long id) {
        return surveyService.getResponse(id);
    }

    @GetMapping("/surveys/{id}/defects")
    public List<DefectResponse> listDefects(@PathVariable long id) {
        surveyService.getSurveyOrThrow(id);
        return defectService.listBySurvey(id);
    }

    @GetMapping("/surveys/{id}/events")
    public List<SurveyEvent> listEvents(@PathVariable long id) {
        surveyService.getSurveyOrThrow(id);
        return eventRepository.findBySurvey(id);
    }

    // ---- 缺陷整改复查 ----

    @PostMapping("/defects/rectify")
    public ApiResult<RectifyResponse> rectify(@Valid @RequestBody RectifyDefectRequest request) {
        return defectService.rectify(request);
    }

    // ---- 发证 ----

    @PostMapping("/certificates/certify")
    public ApiResult<CertifyResponse> certify(@Valid @RequestBody CertifyRequest request) {
        return certificateService.certify(request);
    }

    @GetMapping("/certificates/versions")
    public List<CertificateResponse> versions(@RequestParam String shipImo,
                                               @RequestParam SurveyType type) {
        return certificateQueryService.versions(shipImo, type);
    }

    @GetMapping("/certificates/valid")
    public CertificateResponse valid(@RequestParam String shipImo,
                                     @RequestParam SurveyType type) {
        return certificateQueryService.valid(shipImo, type);
    }

    // ---- 展期 ----

    @PostMapping("/extensions")
    public ApiResult<ExtensionResponse> applyExtension(
            @Valid @RequestBody ApplyExtensionRequest request) {
        return extensionService.apply(request);
    }

    @PostMapping("/extensions/approve")
    public ApiResult<ExtensionResponse> approveExtension(
            @Valid @RequestBody ApproveExtensionRequest request) {
        return extensionService.approve(request);
    }

    // ---- 窗口台账复算 ----

    @PostMapping("/ledger/recompute")
    public java.util.Map<String, Integer> recomputeLedger() {
        return java.util.Map.of("rows", ledgerService.recomputeAll());
    }

    @GetMapping("/ledger")
    public List<com.example.starter.survey.domain.WindowLedgerRow> ledger(
            @RequestParam(required = false) Long shipId) {
        return shipId == null ? ledgerService.listAll() : ledgerService.listByShip(shipId);
    }

    // ---- 船旗国检查报告互认（合成规则）----

    /** 发布新的合成互认规则版本。 */
    @PostMapping("/recognition/rules")
    public ApiResult<RuleVersionResponse> publishRule(
            @Valid @RequestBody PublishRuleRequest request) {
        return recognitionService.publishRule(request);
    }

    /** 列出全部互认规则版本。 */
    @GetMapping("/recognition/rules")
    public List<RuleVersionResponse> listRules() {
        return recognitionService.listRules();
    }

    /** 导入外部检查报告，或对同编号报告更正（生成新修订）。 */
    @PostMapping("/recognition/reports")
    public ApiResult<ReportResponse> importReport(
            @Valid @RequestBody ImportReportRequest request) {
        return recognitionService.importReport(request);
    }

    /** 查询报告当前最新修订。 */
    @GetMapping("/recognition/reports/{reportNo}")
    public ReportResponse getReport(@PathVariable String reportNo,
                                    @RequestParam(required = false) Integer revision) {
        if (revision == null) {
            return recognitionService.currentReport(reportNo);
        }
        return recognitionService.reportRevision(reportNo, revision);
    }

    /** 申请互认绑定。 */
    @PostMapping("/recognition/bindings")
    public ApiResult<BindingResponse> bind(@Valid @RequestBody BindRecognitionRequest request) {
        return recognitionService.bind(request);
    }

    /** 查询某检验的全部互认绑定（含已失效）。 */
    @GetMapping("/recognition/bindings")
    public List<BindingResponse> listBindings(@RequestParam long surveyId) {
        return recognitionService.listBindingsBySurvey(surveyId);
    }

    /** 证据链查询：报告修订链 + 逐项结论 + 绑定 + 风险。 */
    @GetMapping("/recognition/evidence/{reportNo}")
    public EvidenceChainResponse evidenceChain(@PathVariable String reportNo) {
        return recognitionService.evidenceChain(reportNo);
    }
}
