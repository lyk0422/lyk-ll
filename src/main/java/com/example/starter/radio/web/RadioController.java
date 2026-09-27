package com.example.starter.radio.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.starter.radio.model.ChangeOrder;
import com.example.starter.radio.model.DailyCheck;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.DispensingCheck;
import com.example.starter.radio.model.DispensingVersion;
import com.example.starter.radio.model.MotherLiquor;
import com.example.starter.radio.model.Prescription;
import com.example.starter.radio.model.ReconciliationLedger;
import com.example.starter.radio.repository.DispensingRepository;
import com.example.starter.radio.service.CalibratorService;
import com.example.starter.radio.service.ChangeOrderService;
import com.example.starter.radio.service.DispensingService;
import com.example.starter.radio.service.NotFoundException;
import com.example.starter.radio.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 放射性药物活度校准与分装核对 API。
 * 流程：母液登记 → 活度日检 → 分装计算 → 双人核对 → 发药勾稽。
 */
@RestController
@RequestMapping("/api")
public class RadioController {

    private final CalibratorService calibratorService;
    private final DispensingService dispensingService;
    private final RegistrationService registrationService;
    private final ChangeOrderService changeOrderService;
    private final DispensingRepository dispensings;

    public RadioController(CalibratorService calibratorService,
                           DispensingService dispensingService,
                           RegistrationService registrationService,
                           ChangeOrderService changeOrderService,
                           DispensingRepository dispensings) {
        this.calibratorService = calibratorService;
        this.dispensingService = dispensingService;
        this.registrationService = registrationService;
        this.changeOrderService = changeOrderService;
        this.dispensings = dispensings;
    }

    @PostMapping("/calibrators")
    public Map<String, Object> registerCalibrator(@Valid @RequestBody RadioApiDtos.RegisterCalibratorRequest req) {
        long id = registrationService.registerCalibrator(req.code(), req.name());
        return Map.of("id", id, "code", req.code(), "name", req.name());
    }

    @PostMapping("/calibrators/daily-check")
    public Map<String, Object> dailyCheck(@Valid @RequestBody RadioApiDtos.DailyCheckRequest req) {
        DailyCheck check = calibratorService.dailyCheck(req.requestId(), req.calibratorId(),
                req.parsedCheckDate(), req.referenceActivityBq(), req.measuredActivityBq());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", check.id());
        view.put("requestId", check.requestId());
        view.put("calibratorId", check.calibratorId());
        view.put("checkDate", check.checkDate().toString());
        view.put("referenceActivityBq", check.referenceActivityBq());
        view.put("measuredActivityBq", check.measuredActivityBq());
        view.put("deviationRatio", check.deviationRatio());
        view.put("passed", check.passed());
        return view;
    }

    @PostMapping("/mother-liquors")
    public Map<String, Object> registerMotherLiquor(
            @Valid @RequestBody RadioApiDtos.RegisterMotherLiquorRequest req) {
        MotherLiquor mother = registrationService.registerMotherLiquor(req.batchCode(), req.nuclideCode(),
                req.initialActivityBq(), req.referenceTimeEpoch());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", mother.id());
        view.put("batchCode", mother.batchCode());
        view.put("nuclideId", mother.nuclideId());
        view.put("initialActivityBq", mother.initialActivityBq());
        view.put("referenceTime", Instant.ofEpochSecond(mother.referenceTimeEpoch()).toString());
        view.put("remainingActivityBq", mother.remainingActivityBq());
        view.put("status", mother.status());
        return view;
    }

    @PostMapping("/prescriptions")
    public Map<String, Object> registerPrescription(
            @Valid @RequestBody RadioApiDtos.RegisterPrescriptionRequest req) {
        Prescription prescription = registrationService.registerPrescription(req.code(), req.nuclideCode(),
                req.prescribedActivityBq(), req.administrationTimeEpoch());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", prescription.id());
        view.put("code", prescription.code());
        view.put("nuclideId", prescription.nuclideId());
        view.put("prescribedActivityBq", prescription.prescribedActivityBq());
        view.put("administrationTime",
                Instant.ofEpochSecond(prescription.administrationTimeEpoch()).toString());
        return view;
    }

    @PostMapping("/dispensings/calculate")
    public Map<String, Object> calculate(@Valid @RequestBody RadioApiDtos.CalculateRequest req) {
        Dispensing dispensing = dispensingService.calculate(req.requestId(), req.motherLiquorId(),
                req.prescriptionId(), req.calibratorId());
        return dispensingView(dispensing);
    }

    @PostMapping("/dispensings/{id}/checks")
    public Map<String, Object> check(@PathVariable long id,
                                     @Valid @RequestBody RadioApiDtos.CheckRequest req) {
        return dispensingView(dispensingService.check(id, req.checker()));
    }

    @PostMapping("/dispensings/dispense")
    public Map<String, Object> dispense(@Valid @RequestBody RadioApiDtos.DispenseRequest req) {
        DispensingService.DispenseResult result = dispensingService.dispense(req.requestId(),
                req.dispensingId(), req.remainingActivityBq(), req.measuredAtEpoch());
        Map<String, Object> view = dispensingView(result.dispensing());
        view.put("replayed", result.replayed());
        if (result.ledger() != null) {
            view.put("ledger", ledgerView(result.ledger()));
        }
        return view;
    }

    @PostMapping("/dispensings/{id}/measurements")
    public Map<String, Object> recordMeasurement(@PathVariable long id,
                                                 @Valid @RequestBody RadioApiDtos.MeasurementRequest req) {
        String result = dispensingService.recordMeasurement(id, req.payload());
        return Map.of("dispensingId", id, "result", result);
    }

    @GetMapping("/dispensings/{id}")
    public Map<String, Object> getDispensing(@PathVariable long id) {
        Dispensing dispensing = dispensings.findDispensingById(id)
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + id));
        Map<String, Object> view = dispensingView(dispensing);
        List<DispensingCheck> checks = dispensings.findChecks(id);
        view.put("checkChain", checks.stream().map(c -> Map.of(
                "versionNo", (Object) c.versionNo(),
                "checker", c.checker(),
                "checkSeq", c.checkSeq(),
                "checkedAt", Instant.ofEpochSecond(c.checkedAtEpoch()).toString())).toList());
        view.put("versions", changeOrderService.versions(id).stream()
                .map(this::versionView).toList());
        dispensings.findLedger(id).ifPresent(ledger -> view.put("ledger", ledgerView(ledger)));
        return view;
    }

    /**
     * 提交给药时刻变更候选：只形成候选，不立即改有效分装。
     */
    @PostMapping("/dispensings/changes")
    public Map<String, Object> submitChange(@Valid @RequestBody RadioApiDtos.ChangeSubmitRequest req) {
        ChangeOrderService.SubmitResult result = changeOrderService.submit(req.requestId(),
                req.dispensingId(), req.expectedVersion(), req.expectedPrescriptionVersion(),
                req.newAdministrationTimeEpoch());
        Map<String, Object> view = changeView(result.order());
        view.put("replayed", result.replayed());
        return view;
    }

    /**
     * 变更候选双人复核；第二名不同人员复核通过即同事务生效。
     */
    @PostMapping("/dispensings/changes/{id}/reviews")
    public Map<String, Object> reviewChange(@PathVariable long id,
                                            @Valid @RequestBody RadioApiDtos.ChangeReviewRequest req) {
        return changeView(changeOrderService.review(id, req.reviewer()));
    }

    /**
     * 作废变更候选。
     */
    @PostMapping("/dispensings/changes/{id}/void")
    public Map<String, Object> voidChange(@PathVariable long id,
                                          @Valid @RequestBody RadioApiDtos.ChangeVoidRequest req) {
        return changeView(changeOrderService.voidOrder(req.requestId(), id));
    }

    /**
     * 变更候选与复核证据查询。
     */
    @GetMapping("/dispensings/changes/{id}")
    public Map<String, Object> getChange(@PathVariable long id) {
        ChangeOrderService.ChangeEvidence evidence = changeOrderService.evidence(id);
        Map<String, Object> view = changeView(evidence.order());
        view.put("reviewChain", evidence.reviews().stream().map(c -> Map.of(
                "reviewer", (Object) c.reviewer(),
                "reviewSeq", c.reviewSeq(),
                "reviewedAt", Instant.ofEpochSecond(c.reviewedAtEpoch()).toString())).toList());
        return view;
    }

    /**
     * 分装版本计算证据查询：初始版本 + 历次变更版本。
     */
    @GetMapping("/dispensings/{id}/versions")
    public List<Map<String, Object>> getVersions(@PathVariable long id) {
        return changeOrderService.versions(id).stream().map(this::versionView).toList();
    }

    /**
     * 勾稽台账复算：按衰变公式由原始数据重新计算，用于核对已存台账。
     */
    @GetMapping("/dispensings/{id}/reconciliation")
    public Map<String, Object> recomputeReconciliation(@PathVariable long id) {
        return ledgerView(dispensingService.recompute(id));
    }

    private Map<String, Object> dispensingView(Dispensing d) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", d.id());
        view.put("requestId", d.requestId());
        view.put("motherLiquorId", d.motherLiquorId());
        view.put("prescriptionId", d.prescriptionId());
        view.put("calibratorId", d.calibratorId());
        view.put("dispensedActivityBq", d.dispensedActivityBq());
        view.put("deviationRatio", d.deviationRatio());
        view.put("status", d.status().name());
        view.put("versionNo", d.versionNo());
        view.put("activeChangeOrderId", d.activeChangeOrderId());
        view.put("firstChecker", d.firstChecker());
        view.put("secondChecker", d.secondChecker());
        view.put("dispensedAt",
                d.dispensedAtEpoch() == null ? null : Instant.ofEpochSecond(d.dispensedAtEpoch()).toString());
        return view;
    }

    private Map<String, Object> changeView(ChangeOrder c) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", c.id());
        view.put("requestId", c.requestId());
        view.put("dispensingId", c.dispensingId());
        view.put("expectedVersion", c.expectedVersion());
        view.put("prescriptionVersion", c.prescriptionVersion());
        view.put("newAdministrationTime",
                Instant.ofEpochSecond(c.newAdministrationTimeEpoch()).toString());
        view.put("candidateVersionNo", c.candidateVersionNo());
        view.put("candidateActivityBq", c.candidateActivityBq());
        view.put("candidateDeviationRatio", c.candidateDeviationRatio());
        view.put("calcStatus", c.calcStatus());
        view.put("status", c.status().name());
        view.put("firstReviewer", c.firstReviewer());
        view.put("secondReviewer", c.secondReviewer());
        view.put("approvedAt",
                c.approvedAtEpoch() == null ? null : Instant.ofEpochSecond(c.approvedAtEpoch()).toString());
        view.put("voidedAt",
                c.voidedAtEpoch() == null ? null : Instant.ofEpochSecond(c.voidedAtEpoch()).toString());
        return view;
    }

    private Map<String, Object> versionView(DispensingVersion v) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("versionNo", v.versionNo());
        view.put("prescriptionId", v.prescriptionId());
        view.put("administrationTime",
                Instant.ofEpochSecond(v.administrationTimeEpoch()).toString());
        view.put("dispensedActivityBq", v.dispensedActivityBq());
        view.put("deviationRatio", v.deviationRatio());
        view.put("calcStatus", v.calcStatus());
        view.put("changeOrderId", v.changeOrderId());
        return view;
    }

    private Map<String, Object> ledgerView(ReconciliationLedger ledger) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("dispensingId", ledger.dispensingId());
        view.put("initialActivityBq", ledger.initialActivityBq());
        view.put("dispensedTotalBq", ledger.dispensedTotalBq());
        view.put("remainingActivityBq", ledger.remainingActivityBq());
        view.put("decayResidualBq", ledger.decayResidualBq());
        view.put("balanced", ledger.balanced());
        view.put("measuredAt", Instant.ofEpochSecond(ledger.measuredAtEpoch()).toString());
        return view;
    }
}
