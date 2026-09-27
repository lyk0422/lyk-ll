package com.example.starter.compliance.web;

import com.example.starter.compliance.service.ClearanceService;
import com.example.starter.compliance.service.Results;
import com.example.starter.compliance.service.ServiceResult;
import com.example.starter.compliance.web.Requests.ClearanceCertificateRequest;
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
 * 换配方清线受控入口（合成规则，仅演示，不用于生产）：
 * 过敏原目录与配方版本过敏原登记、生产线登记、清线凭证双人确认、受控量产门禁受理及证据查询。
 *
 * <p>原未指定生产线的量产上报入口（/api/compliance/production-records）保持兼容，
 * 本受控入口（/api/compliance/controlled-productions）不得绕过过敏原清线门禁。
 */
@RestController
@RequestMapping("/api/compliance")
public class ClearanceController {

    private final ClearanceService service;

    public ClearanceController(ClearanceService service) {
        this.service = service;
    }

    /** 过敏原合成目录。 */
    @GetMapping("/allergens")
    public List<Results.AllergenView> allergens() {
        return service.listAllergens();
    }

    /** 为配方版本登记/修改过敏原集合（expectedVersion 缺失为首次登记，version 从 1 起、成功变更 +1）。 */
    @PostMapping("/formulas/allergens")
    public ResponseEntity<Results.FormulaAllergenView> registerAllergens(
            @RequestBody FormulaAllergenRequest request) {
        return respond(service.registerFormulaAllergens(request));
    }

    @GetMapping("/formulas/{formulaId}/allergens")
    public Results.FormulaAllergenView formulaAllergens(@PathVariable long formulaId) {
        return service.getFormulaAllergens(formulaId);
    }

    /** 登记生产线：最近量产版本为空，占用版本与变更版本从 1 开始。 */
    @PostMapping("/lines")
    public ResponseEntity<Results.ProductionLineView> registerLine(
            @RequestBody ProductionLineRequest request) {
        return respond(service.registerLine(request));
    }

    @GetMapping("/lines/{lineCode}")
    public Results.ProductionLineView line(@PathVariable String lineCode) {
        return service.getLine(lineCode);
    }

    /** 清线凭证双人确认：操作人与复核人必须不同，固定线路、清线时刻与当时占用版本。 */
    @PostMapping("/clearance-certificates")
    public ResponseEntity<Results.ClearanceCertificateView> confirmClearance(
            @RequestBody ClearanceCertificateRequest request) {
        return respond(service.confirmClearance(request));
    }

    @GetMapping("/clearance-certificates/{certificateId}")
    public Results.ClearanceCertificateView certificate(@PathVariable long certificateId) {
        return service.getCertificate(certificateId);
    }

    /** 受控量产受理：校验备案有效性、凭证范围与线路占用版本，原子写记录、推进版本、核销凭证。 */
    @PostMapping("/controlled-productions")
    public ResponseEntity<Results.ControlledProductionView> controlledProduction(
            @RequestBody ControlledProductionRequest request) {
        return respond(service.declareControlledProduction(request));
    }

    /** 按线路查询受控量产历史（不可变证据链）。 */
    @GetMapping("/lines/{lineCode}/controlled-productions")
    public List<Results.ControlledProductionView> lineProductions(@PathVariable String lineCode) {
        return service.listControlledProductions(lineCode);
    }

    private static <T> ResponseEntity<T> respond(ServiceResult<T> result) {
        return ResponseEntity.status(result.status())
                .header("X-Idempotent-Replay", String.valueOf(result.replayed()))
                .body(result.body());
    }
}
