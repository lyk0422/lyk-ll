package com.example.starter.compliance.web;

import com.example.starter.compliance.service.LineControlResults;
import com.example.starter.compliance.service.LineControlService;
import com.example.starter.compliance.service.ServiceResult;
import com.example.starter.compliance.web.Requests.ConfirmCertificateRequest;
import com.example.starter.compliance.web.Requests.ControlledProductionRequest;
import com.example.starter.compliance.web.Requests.FormulaAllergenRequest;
import com.example.starter.compliance.web.Requests.RegisterLineRequest;
import com.example.starter.compliance.web.Requests.RegisterReworkLotRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 清线凭证门禁 HTTP 入口（新增受控量产入口，原 /production-records 未指定生产线入口保持不变）：
 * 配方版本过敏原登记、生产线登记、清线凭证双人确认、受控量产与查询。
 */
@RestController
@RequestMapping("/api/compliance/line-control")
public class LineControlController {

    private final LineControlService service;

    public LineControlController(LineControlService service) {
        this.service = service;
    }

    /** 配方版本过敏原集合新增/修改：expectedVersion 新增传 0，修改传当前版本，成功后版本加 1。 */
    @PostMapping("/formula-allergens")
    public ResponseEntity<LineControlResults.FormulaAllerView> registerAllergens(
            @RequestBody FormulaAllergenRequest request) {
        return respond(service.registerFormulaAllergens(request));
    }

    @GetMapping("/formula-allergens")
    public LineControlResults.FormulaAllerView allergen(@RequestParam String productCode,
            @RequestParam int formulaVersion) {
        return service.getAllergenRegistration(productCode, formulaVersion);
    }

    /** 生产线登记：最近一次量产版本与递增占用版本（初始 1）。 */
    @PostMapping("/lines")
    public ResponseEntity<LineControlResults.LineView> registerLine(
            @RequestBody RegisterLineRequest request) {
        return respond(service.registerLine(request));
    }

    @GetMapping("/lines/{lineCode}")
    public LineControlResults.LineView line(@PathVariable String lineCode) {
        return service.getLine(lineCode);
    }

    @GetMapping("/lines/{lineCode}/productions")
    public LineControlResults.LineProductionView lineProductions(@PathVariable String lineCode) {
        return service.getLineProductions(lineCode);
    }

    /** 清线凭证双人确认：固定线路、清线时刻及当时占用版本，确认后 2 小时内有效。 */
    @PostMapping("/clearance-certificates")
    public ResponseEntity<LineControlResults.CertificateView> confirmCertificate(
            @RequestBody ConfirmCertificateRequest request) {
        return respond(service.confirmCertificate(request));
    }

    @GetMapping("/clearance-certificates/{certificateId}")
    public LineControlResults.CertificateView certificate(@PathVariable long certificateId) {
        return service.getCertificate(certificateId);
    }

    /** 受控量产：过敏原清线门禁 + 备案有效性 + 线路版本，原子受理，同线路版本并发至多一笔成功。 */
    @PostMapping("/controlled-productions")
    public ResponseEntity<LineControlResults.ControlledProductionView> controlledProduction(
            @RequestBody ControlledProductionRequest request) {
        return respond(service.controlledProduction(request));
    }

    /** 返工料登记：引用一条已成功量产记录，冻结来源配方版本及过敏原集合，同来源只登记一次。 */
    @PostMapping("/rework-lots")
    public ResponseEntity<LineControlResults.ReworkLotView> registerReworkLot(
            @RequestBody RegisterReworkLotRequest request) {
        return respond(service.registerReworkLot(request));
    }

    /** 返工料账勾稽查询：来源、原量、逐笔消费与余额。 */
    @GetMapping("/rework-lots/{lotCode}")
    public LineControlResults.ReworkLotLedgerView reworkLot(@PathVariable String lotCode) {
        return service.getReworkLotLedger(lotCode);
    }

    private static <T> ResponseEntity<T> respond(ServiceResult<T> result) {
        return ResponseEntity.status(result.status())
                .header("X-Idempotent-Replay", String.valueOf(result.replayed()))
                .body(result.body());
    }
}
