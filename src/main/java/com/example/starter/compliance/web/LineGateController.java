package com.example.starter.compliance.web;

import com.example.starter.compliance.service.LineGateService;
import com.example.starter.compliance.service.Results;
import com.example.starter.compliance.service.ServiceResult;
import com.example.starter.compliance.web.Requests.CleaningCertificateRequest;
import com.example.starter.compliance.web.Requests.ControlledProductionRequest;
import com.example.starter.compliance.web.Requests.FormulaAllergenRequest;
import com.example.starter.compliance.web.Requests.ProductionLineRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产线过敏原清线凭证门禁 HTTP 入口：
 * 配方过敏原登记、生产线登记、清线凭证双人确认、受控量产受理与门禁查询。
 * 原未指定生产线的量产入口保持兼容，本受控入口不得绕过清线门禁。
 */
@RestController
@RequestMapping("/api/compliance")
public class LineGateController {

    private final LineGateService service;

    public LineGateController(LineGateService service) {
        this.service = service;
    }

    /** 为配方版本登记/修改过敏原集合（expectedVersion 乐观锁，首次为 0，成功后版本从 1 起递增）。 */
    @PostMapping("/formula-allergens")
    public ResponseEntity<Results.FormulaAllergenView> registerAllergens(
            @RequestBody FormulaAllergenRequest request) {
        return respond(service.registerFormulaAllergens(request));
    }

    @GetMapping("/formula-allergens/{formulaId}")
    public Results.FormulaAllergenView allergens(@PathVariable long formulaId) {
        return service.getFormulaAllergens(formulaId);
    }

    /** 登记生产线（占用版本初始为 1，最近量产配方为空）。 */
    @PostMapping("/production-lines")
    public ResponseEntity<Results.ProductionLineView> registerLine(
            @RequestBody ProductionLineRequest request) {
        return respond(service.registerLine(request));
    }

    @GetMapping("/production-lines/{lineCode}")
    public Results.ProductionLineView line(@PathVariable String lineCode) {
        return service.getLine(lineCode);
    }

    /** 清线凭证双人共同确认；固定线路版本与清线时刻，UTC 下 2 小时内有效（恰满 2 小时失效）。 */
    @PostMapping("/cleaning-certificates")
    public ResponseEntity<Results.CleaningCertificateView> confirmCleaning(
            @RequestBody CleaningCertificateRequest request) {
        return respond(service.confirmCleaning(request));
    }

    @GetMapping("/cleaning-certificates/{certificateId}")
    public Results.CleaningCertificateView certificate(@PathVariable long certificateId) {
        return service.getCertificate(certificateId);
    }

    /** 线路全部清线凭证（按确认先后），供核对凭证状态与作废链。 */
    @GetMapping("/production-lines/{lineCode}/cleaning-certificates")
    public List<Results.CleaningCertificateView> lineCertificates(@PathVariable String lineCode) {
        return service.getLineCertificates(lineCode);
    }

    /** 受控量产受理：同时校验备案有效、凭证范围与线路版本，原子写记录、推进线路、核销凭证。 */
    @PostMapping("/controlled-productions")
    public ResponseEntity<Results.ControlledProductionView> controlledProduction(
            @RequestBody ControlledProductionRequest request) {
        return respond(service.acceptControlledProduction(request));
    }

    /** 线路全部受控量产记录（按受理先后），供核对占用版本序列与凭证核销链。 */
    @GetMapping("/production-lines/{lineCode}/controlled-productions")
    public List<Results.ControlledProductionView> lineProductions(@PathVariable String lineCode) {
        return service.getLineProductions(lineCode);
    }

    private static <T> ResponseEntity<T> respond(ServiceResult<T> result) {
        return ResponseEntity.status(result.status())
                .header("X-Idempotent-Replay", String.valueOf(result.replayed()))
                .body(result.body());
    }
}
