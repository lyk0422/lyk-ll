package com.example.starter.radio.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.starter.radio.model.DailyCheck;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.DispensingCheck;
import com.example.starter.radio.model.DispensingVersion;
import com.example.starter.radio.model.DoseChangeCheck;
import com.example.starter.radio.model.DoseChangeOrder;
import com.example.starter.radio.model.MotherLiquor;
import com.example.starter.radio.model.Prescription;
import com.example.starter.radio.model.ReconciliationLedger;
import com.example.starter.radio.repository.DispensingRepository;
import com.example.starter.radio.service.CalibratorService;
import com.example.starter.radio.service.DispensingService;
import com.example.starter.radio.service.DoseChangeService;
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
    private final DoseChangeService doseChangeService;
    private final DispensingRepository dispensings;

    public RadioController(CalibratorService calibratorService,
                           DispensingService dispensingService,
                           RegistrationService registrationService,
                           DoseChangeService doseChangeService,
                           DispensingRepository dispensings) {
        this.calibratorService = calibratorService;
        this.dispensingService = dispensingService;
        this.registrationService = registrationService;
        this.doseChangeService = doseChangeService;
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
                "checker", (Object) c.checker(),
                "checkSeq", c.checkSeq(),
                "checkedAt", Instant.ofEpochSecond(c.checkedAtEpoch()).toString())).toList());
        dispensings.findLedger(id).ifPresent(ledger -> view.put("ledger", ledgerView(ledger)));
        return view;
    }

    /**
     * 勾稽台账复算：按衰变公式由原始数据重新计算，用于核对已存台账。
     */
    @GetMapping("/dispensings/{id}/reconciliation")
    public Map<String, Object> recomputeReconciliation(@PathVariable long id) {
        return ledgerView(dispensingService.recompute(id));
    }

    /** 提交未发药分装的给药时刻变更候选。 */
    @PostMapping("/dispensings/{id}/changes")
    public Map<String, Object> submitChange(@PathVariable long id,
                                            @Valid @RequestBody RadioApiDtos.ChangeSubmitRequest req) {
        DoseChangeService.ChangeOutcome outcome = doseChangeService.submit(req.requestId(), id,
                req.expectedVersion(), req.newAdministrationTimeEpoch());
        return changeOutcomeView(outcome);
    }

    /** 变更候选双人复核；两名不同人员齐备的第二签同事务生效。 */
    @PostMapping("/changes/{changeId}/reviews")
    public Map<String, Object> reviewChange(@PathVariable long changeId,
                                            @Valid @RequestBody RadioApiDtos.ChangeReviewRequest req) {
        DoseChangeService.ChangeOutcome outcome = doseChangeService.review(
                req.requestId(), changeId, req.checker());
        return changeOutcomeView(outcome);
    }

    /** 作废待决变更候选。 */
    @PostMapping("/changes/{changeId}/cancel")
    public Map<String, Object> cancelChange(@PathVariable long changeId,
                                            @Valid @RequestBody RadioApiDtos.ChangeCancelRequest req) {
        DoseChangeService.ChangeOutcome outcome = doseChangeService.cancel(req.requestId(), changeId);
        return changeOutcomeView(outcome);
    }

    /** 版本计算证据：某分装的全部计算版本（含已 SUPERSEDED 旧版本）。 */
    @GetMapping("/dispensings/{id}/versions")
    public Map<String, Object> versions(@PathVariable long id) {
        List<DispensingVersion> versions = doseChangeService.versions(id);
        return Map.of("dispensingId", id,
                "versions", versions.stream().map(this::versionView).toList());
    }

    /** 变更候选证据：某分装的全部候选单与各自双人复核链。 */
    @GetMapping("/dispensings/{id}/changes")
    public Map<String, Object> changes(@PathVariable long id) {
        List<DoseChangeOrder> orders = doseChangeService.changeOrders(id);
        return Map.of("dispensingId", id,
                "changes", orders.stream().map(o -> {
                    Map<String, Object> view = changeOrderView(o);
                    view.put("reviewChain", doseChangeService.changeChecks(o.id()).stream()
                            .map(this::changeCheckView).toList());
                    return view;
                }).toList());
    }

    private Map<String, Object> dispensingView(Dispensing d) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", d.id());
        view.put("requestId", d.requestId());
        view.put("motherLiquorId", d.motherLiquorId());
        view.put("prescriptionId", d.prescriptionId());
        view.put("calibratorId", d.calibratorId());
        view.put("version", d.version());
        view.put("administrationTime", Instant.ofEpochSecond(d.administrationTimeEpoch()).toString());
        view.put("dispensedActivityBq", d.dispensedActivityBq());
        view.put("deviationRatio", d.deviationRatio());
        view.put("status", d.status().name());
        view.put("firstChecker", d.firstChecker());
        view.put("secondChecker", d.secondChecker());
        view.put("dispensedAt",
                d.dispensedAtEpoch() == null ? null : Instant.ofEpochSecond(d.dispensedAtEpoch()).toString());
        return view;
    }

    private Map<String, Object> changeOutcomeView(DoseChangeService.ChangeOutcome outcome) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("phase", outcome.phase());
        view.put("replayed", outcome.replayed());
        view.put("change", changeOrderView(outcome.order()));
        view.put("dispensing", dispensingView(outcome.dispensing()));
        return view;
    }

    private Map<String, Object> changeOrderView(DoseChangeOrder o) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", o.id());
        view.put("requestId", o.requestId());
        view.put("dispensingId", o.dispensingId());
        view.put("expectedVersion", o.expectedVersion());
        view.put("prescriptionId", o.prescriptionId());
        view.put("newAdministrationTime",
                Instant.ofEpochSecond(o.newAdministrationTimeEpoch()).toString());
        view.put("candidateVersion", o.candidateVersion());
        view.put("candidateActivityBq", o.candidateActivityBq());
        view.put("candidateDeviationRatio", o.candidateDeviationRatio());
        view.put("inventoryAvailableBq", o.inventoryAvailableBq());
        view.put("status", o.status().name());
        view.put("firstReviewer", o.firstReviewer());
        view.put("secondReviewer", o.secondReviewer());
        view.put("decidedAt",
                o.decidedAtEpoch() == null ? null : Instant.ofEpochSecond(o.decidedAtEpoch()).toString());
        return view;
    }

    private Map<String, Object> versionView(DispensingVersion v) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("version", v.version());
        view.put("administrationTime", Instant.ofEpochSecond(v.administrationTimeEpoch()).toString());
        view.put("dispensedActivityBq", v.dispensedActivityBq());
        view.put("deviationRatio", v.deviationRatio());
        view.put("motherInitialActivityBq", v.motherInitialActivityBq());
        view.put("motherReferenceTime", Instant.ofEpochSecond(v.motherReferenceTimeEpoch()).toString());
        view.put("halfLifeSeconds", v.halfLifeSeconds());
        view.put("source", v.source());
        view.put("changeOrderId", v.changeOrderId());
        view.put("status", v.status().name());
        return view;
    }

    private Map<String, Object> changeCheckView(DoseChangeCheck c) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("checker", c.checker());
        view.put("checkSeq", c.checkSeq());
        view.put("checkedAt", Instant.ofEpochSecond(c.checkedAtEpoch()).toString());
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
