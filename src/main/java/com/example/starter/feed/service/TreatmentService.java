package com.example.starter.feed.service;

import com.example.starter.feed.domain.AlertType;
import com.example.starter.feed.domain.BatchStatus;
import com.example.starter.feed.dto.TreatmentReportRequest;
import com.example.starter.feed.dto.TreatmentReportResponse;
import com.example.starter.feed.repo.AlertRepository;
import com.example.starter.feed.repo.BatchRepository;
import com.example.starter.feed.repo.DictRepository;
import com.example.starter.feed.repo.SlaughterApplicationRepository;
import com.example.starter.feed.repo.TreatmentReportRepository;
import com.example.starter.feed.web.ApiException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 治疗性用药直报：养殖端按批次上报治疗用药（合成兽药目录），受理后触发批次休药期重算。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>须两名不同的养殖端人员确认，单人直报不生效；</li>
 *   <li>目录外药品名称拒绝受理（422），且不改变原出栏日与任何状态；</li>
 *   <li>重算按最大休药天数与最晚给药时刻合并（与含药饲喂统一口径），原最早出栏日作废；</li>
 *   <li>已批准的预约出栏申请与新最早出栏日冲突时在同一事务内自动撤销，撤销记录保留可查；</li>
 *   <li>requestId 幂等：同键同参返回首次结果，同键异参 409；同批次同给药时刻仅允许一条直报；</li>
 *   <li>批次已出栏核销（终态）时直报只记迟到事件并告警，不改变终态与休药字段。</li>
 * </ul>
 */
@Service
public class TreatmentService {

    /** 幂等请求类型：治疗用药直报。 */
    public static final String KIND_TREATMENT_REPORT = "TREATMENT_REPORT";

    private final BatchRepository batchRepository;
    private final DictRepository dictRepository;
    private final TreatmentReportRepository treatmentReportRepository;
    private final SlaughterApplicationRepository slaughterApplicationRepository;
    private final AlertRepository alertRepository;
    private final WithdrawalRecalcService withdrawalRecalcService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public TreatmentService(BatchRepository batchRepository,
                            DictRepository dictRepository,
                            TreatmentReportRepository treatmentReportRepository,
                            SlaughterApplicationRepository slaughterApplicationRepository,
                            AlertRepository alertRepository,
                            WithdrawalRecalcService withdrawalRecalcService,
                            IdempotencyService idempotencyService,
                            Clock clock) {
        this.batchRepository = batchRepository;
        this.dictRepository = dictRepository;
        this.treatmentReportRepository = treatmentReportRepository;
        this.slaughterApplicationRepository = slaughterApplicationRepository;
        this.alertRepository = alertRepository;
        this.withdrawalRecalcService = withdrawalRecalcService;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 治疗用药直报（requestId 幂等）。
     */
    public IdempotencyService.Result<TreatmentReportResponse> reportTreatment(TreatmentReportRequest request) {
        IdempotencyService.Result<TreatmentReportResponse> result = idempotencyService.execute(
                request.requestId(),
                KIND_TREATMENT_REPORT,
                request,
                () -> doReport(request),
                TreatmentReportResponse.class);
        if (result.replayed() && !result.response().replayed()) {
            TreatmentReportResponse r = result.response();
            return new IdempotencyService.Result<>(
                    new TreatmentReportResponse(r.requestId(), r.batchNo(), true, r.accepted(),
                            r.rejectReason(), r.late(), r.alerted(), r.administeredAtUtc(),
                            r.governingWithdrawalDays(), r.withdrawalUntilDate(),
                            r.earliestSlaughterUtc(), r.revokedApplicationIds()),
                    result.httpStatus(), true);
        }
        return result;
    }

    private IdempotencyService.Outcome doReport(TreatmentReportRequest request) {
        // 双人确认：须两名不同的养殖端人员，单人直报不生效（确定性拒绝，以 422 落库）。
        List<String> confirmers = request.confirmers();
        if (confirmers.get(0).equals(confirmers.get(1))) {
            return rejected(request, "治疗直报须两名不同的养殖端人员确认，单人直报不生效");
        }

        // 合成兽药目录校验：目录外药品名称拒绝受理，且不改变原出栏日与任何状态。
        Map<String, DictRepository.TreatmentDrugDict> drugs = new LinkedHashMap<>();
        for (String name : request.drugNames()) {
            Optional<DictRepository.TreatmentDrugDict> drug = dictRepository.findActiveTreatmentDrugByName(name);
            if (drug.isEmpty()) {
                return rejected(request, "兽药目录外的药品名称拒绝受理: " + name);
            }
            drugs.putIfAbsent(drug.get().code(), drug.get());
        }

        // 行锁串行化同一批次的并发直报/投产/饲喂/出栏，保证同一时刻只有一条进行中直报。
        BatchRepository.BatchRow batch = batchRepository.lockAndGet(request.batchNo());
        boolean late = BatchStatus.SLAUGHTERED.name().equals(batch.status());

        int reportMaxDays = drugs.values().stream()
                .mapToInt(DictRepository.TreatmentDrugDict::withdrawalDays)
                .max().orElse(0);
        String fingerprint = FingerprintUtil.sha256(request);
        Optional<Long> reportIdOpt = treatmentReportRepository.insertReport(
                request.requestId(), batch.id(), request.administeredAtUtc(), reportMaxDays,
                confirmers.get(0), confirmers.get(1), late, fingerprint);
        if (reportIdOpt.isEmpty()) {
            throw ApiException.conflict("同一批次同一给药时刻已存在直报，不允许重复直报: "
                    + request.batchNo() + " @ " + request.administeredAtUtc());
        }
        long reportId = reportIdOpt.get();
        for (DictRepository.TreatmentDrugDict drug : drugs.values()) {
            treatmentReportRepository.insertDrug(reportId, drug.code(), drug.name(), drug.withdrawalDays());
        }

        if (late) {
            // 出栏核销终态语义不变：迟到直报只记迟到事件并告警，不重算、不撤销。
            alertRepository.insert(AlertType.LATE_TREATMENT_REPORT.name(), batch.batchNo(), null,
                    "批次已于 " + batch.slaughteredAtUtc() + " 出栏核销（终态），收到迟到治疗用药直报（reportId="
                            + reportId + "，给药时刻 " + request.administeredAtUtc() + "，药品 "
                            + request.drugNames() + "），仅记录事件并告警");
            return IdempotencyService.Outcome.ok(new TreatmentReportResponse(
                    request.requestId(), batch.batchNo(), false, true, null, true, true,
                    request.administeredAtUtc(),
                    batch.governingWithdrawalDays(), batch.withdrawalUntilDate(),
                    batch.withdrawalUntilDate() == null ? null
                            : WithdrawalCalculator.earliestSlaughter(batch.withdrawalUntilDate()),
                    List.of()));
        }

        // 重算与撤销在同一事务内完成：原最早出栏日作废，冲突的已批准预约自动撤销。
        WithdrawalCalculator.MergedWithdrawal merged =
                withdrawalRecalcService.recomputeAndApply(batch.id());
        List<Long> revoked = new ArrayList<>();
        if (merged != null) {
            revoked = slaughterApplicationRepository.revokeConflicting(
                    batch.id(), merged.earliestSlaughterUtc(), clock.instant(),
                    "治疗性用药直报触发休药期重算，新最早合法出栏时刻(UTC)=" + merged.earliestSlaughterUtc()
                            + "，原已批准预约出栏申请与新最早出栏日冲突，自动撤销");
        }

        return IdempotencyService.Outcome.ok(new TreatmentReportResponse(
                request.requestId(), batch.batchNo(), false, true, null, false, false,
                request.administeredAtUtc(),
                merged == null ? null : merged.maxWithdrawalDays(),
                merged == null ? null : merged.withdrawalUntilDate(),
                merged == null ? null : merged.earliestSlaughterUtc(),
                revoked));
    }

    /**
     * 确定性拒绝受理：以 422 落库为首次结果，同键重放返回同一拒绝；不产生任何业务状态变更。
     */
    private IdempotencyService.Outcome rejected(TreatmentReportRequest request, String reason) {
        return new IdempotencyService.Outcome(new TreatmentReportResponse(
                request.requestId(), request.batchNo(), false, false, reason, false, false,
                request.administeredAtUtc(), null, null, null, List.of()), 422);
    }
}
