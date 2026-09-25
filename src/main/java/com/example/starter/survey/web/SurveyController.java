package com.example.starter.survey.web;

import com.example.starter.survey.api.Dtos;
import com.example.starter.survey.domain.SurveyEventRow;
import com.example.starter.survey.service.Idempotency;
import com.example.starter.survey.service.SurveyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 船级检验周期与展期审批接口。
 *
 * <p>流程：申请检验 → 登轮检验 → 缺陷整改/复查 → 发证；年度检验可申请并批准一次展期。
 * 所有写接口以 requestId 幂等，日期均为 UTC 日历日（ISO yyyy-MM-dd）。</p>
 */
@RestController
@RequestMapping("/api/surveys")
public class SurveyController {

    private final SurveyService service;

    public SurveyController(SurveyService service) {
        this.service = service;
    }

    @GetMapping("/ships")
    public List<Dtos.ShipView> ships() {
        return service.listShips();
    }

    @PostMapping("/apply")
    public ResponseEntity<Object> apply(@Valid @RequestBody Dtos.ApplySurveyRequest req) {
        return toEntity(service.applySurvey(req));
    }

    @PostMapping("/board")
    public ResponseEntity<Object> board(@Valid @RequestBody Dtos.BoardSurveyRequest req) {
        return toEntity(service.boardSurvey(req));
    }

    @PostMapping("/defects/rectify")
    public ResponseEntity<Object> rectify(@Valid @RequestBody Dtos.RectifyDefectRequest req) {
        return toEntity(service.rectifyDefect(req));
    }

    @PostMapping("/defects/recheck")
    public ResponseEntity<Object> recheck(@Valid @RequestBody Dtos.RecheckDefectRequest req) {
        return toEntity(service.recheckDefect(req));
    }

    @PostMapping("/issue")
    public ResponseEntity<Object> issue(@Valid @RequestBody Dtos.IssueCertificateRequest req) {
        return toEntity(service.issueCertificate(req));
    }

    @PostMapping("/extensions/apply")
    public ResponseEntity<Object> applyExtension(@Valid @RequestBody Dtos.ApplyExtensionRequest req) {
        return toEntity(service.applyExtension(req));
    }

    @PostMapping("/extensions/approve")
    public ResponseEntity<Object> approveExtension(
            @Valid @RequestBody Dtos.ApproveExtensionRequest req) {
        return toEntity(service.approveExtension(req));
    }

    @PostMapping("/late-record")
    public ResponseEntity<Object> lateRecord(@Valid @RequestBody Dtos.LateRecordRequest req) {
        return toEntity(service.lateRecord(req));
    }

    @PostMapping("/recompute")
    public ResponseEntity<Object> recompute(@Valid @RequestBody Dtos.RecomputeRequest req) {
        return toEntity(service.recompute(req));
    }

    @GetMapping("/{surveyId}")
    public Dtos.SurveyView survey(@PathVariable long surveyId) {
        return service.getSurvey(surveyId);
    }

    @GetMapping("/{surveyId}/defects")
    public List<Dtos.DefectView> defects(@PathVariable long surveyId) {
        return service.listDefects(surveyId);
    }

    @GetMapping("/{surveyId}/events")
    public List<SurveyEventRow> events(@PathVariable long surveyId) {
        return service.listEvents(surveyId);
    }

    @GetMapping("/certificates/valid")
    public ResponseEntity<Dtos.CertificateView> validCertificate(
            @RequestParam String imo, @RequestParam String typeCode) {
        Dtos.CertificateView view = service.getValidCertificate(imo, typeCode);
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }

    @GetMapping("/certificates/chain")
    public List<Dtos.CertificateView> certificateChain(
            @RequestParam String imo, @RequestParam String typeCode) {
        return service.listCertificateChain(imo, typeCode);
    }

    @GetMapping("/ledger")
    public List<Dtos.LedgerView> ledger(@RequestParam String imo) {
        return service.listLedger(imo);
    }

    @GetMapping("/extensions/{extensionId}")
    public Dtos.ExtensionView extension(@PathVariable long extensionId) {
        return service.getExtension(extensionId);
    }

    private ResponseEntity<Object> toEntity(Idempotency.Result result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
