package com.example.starter.feed.service;

import com.example.starter.feed.domain.AlertType;
import com.example.starter.feed.domain.BatchStatus;
import com.example.starter.feed.domain.FormulaStatus;
import com.example.starter.feed.dto.BatchCreateRequest;
import com.example.starter.feed.dto.BatchStatusResponse;
import com.example.starter.feed.dto.FeedingRecordRequest;
import com.example.starter.feed.dto.FeedingRecordResponse;
import com.example.starter.feed.dto.ProductionRequest;
import com.example.starter.feed.dto.ProductionResponse;
import com.example.starter.feed.dto.SlaughterRequest;
import com.example.starter.feed.dto.SlaughterResponse;
import com.example.starter.feed.dto.SlaughterRevocationResponse;
import com.example.starter.feed.dto.TreatmentReportRequest;
import com.example.starter.feed.dto.TreatmentReportResponse;
import com.example.starter.feed.repo.AlertRepository;
import com.example.starter.feed.repo.BatchRepository;
import com.example.starter.feed.repo.DictRepository;
import com.example.starter.feed.repo.FormulaRepository;
import com.example.starter.feed.repo.ProductionRepository;
import com.example.starter.feed.repo.SlaughterApplicationRepository;
import com.example.starter.feed.repo.TreatmentReportRepository;
import com.example.starter.feed.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 养殖批次：登记、含药配方投产与休药期合并、含药饲喂（含迟到告警）、出栏判定、
 * 治疗性用药直报（双人确认、休药期重算、冲突出栏申请同事务撤销）。
 */
@Service
public class BatchService {

    /** 幂等请求类型：出栏申请。 */
    public static final String KIND_SLAUGHTER_APPLY = "SLAUGHTER_APPLY";
    /** 幂等请求类型：治疗性用药直报。 */
    public static final String KIND_TREATMENT_REPORT = "TREATMENT_REPORT";

    private final BatchRepository batchRepository;
    private final FormulaRepository formulaRepository;
    private final ProductionRepository productionRepository;
    private final DictRepository dictRepository;
    private final AlertRepository alertRepository;
    private final TreatmentReportRepository treatmentReportRepository;
    private final SlaughterApplicationRepository slaughterApplicationRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public BatchService(BatchRepository batchRepository,
                        FormulaRepository formulaRepository,
                        ProductionRepository productionRepository,
                        DictRepository dictRepository,
                        AlertRepository alertRepository,
                        TreatmentReportRepository treatmentReportRepository,
                        SlaughterApplicationRepository slaughterApplicationRepository,
                        IdempotencyService idempotencyService,
                        Clock clock) {
        this.batchRepository = batchRepository;
        this.formulaRepository = formulaRepository;
        this.productionRepository = productionRepository;
        this.dictRepository = dictRepository;
        this.alertRepository = alertRepository;
        this.treatmentReportRepository = treatmentReportRepository;
        this.slaughterApplicationRepository = slaughterApplicationRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 登记合成养殖批次。
     */
    @Transactional
    public BatchStatusResponse createBatch(BatchCreateRequest request) {
        try {
            batchRepository.insert(request.batchNo(), request.species());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("养殖批次号已存在: " + request.batchNo());
        }
        return toStatusResponse(batchRepository.get(request.batchNo()));
    }

    @Transactional(readOnly = true)
    public BatchStatusResponse getBatch(String batchNo) {
        return toStatusResponse(batchRepository.get(batchNo));
    }

    /**
     * 含药（或无药）配方投产到批次：
     * 仅 APPROVED 版本可投产、种类须一致；含药配方写入批准版本药物快照并合并休药期。
     * 同批次并发投产两个含药配方时按行锁串行化，休药期取各药物最大休药天数与最晚末次饲喂。
     */
    @Transactional
    public ProductionResponse produce(ProductionRequest request) {
        FormulaRepository.FormulaRow formula =
                formulaRepository.get(request.formulaCode(), request.version());
        if (formula.status().equals(FormulaStatus.REJECTED.name())) {
            throw ApiException.unprocessable("合规拒绝的配方不可投产: "
                    + request.formulaCode() + " v" + request.version());
        }
        if (formula.status().equals(FormulaStatus.SUBMITTED.name())) {
            throw ApiException.unprocessable("配方尚未批准，不可投产: "
                    + request.formulaCode() + " v" + request.version());
        }

        // 行锁串行化同一批次的并发投产/饲喂/出栏。
        BatchRepository.BatchRow batch = batchRepository.lockAndGet(request.batchNo());
        // 含药配方必须绑定目标动物种类（提交时已校验非空），投产时须与批次种类一致；无药配方不限制。
        if (formula.containsDrug() && !batch.species().equals(formula.targetSpecies())) {
            throw ApiException.unprocessable("配方目标动物种类 " + formula.targetSpecies()
                    + " 与批次种类 " + batch.species() + " 不一致，拒绝投产");
        }
        if (BatchStatus.SLAUGHTERED.name().equals(batch.status())) {
            throw ApiException.unprocessable("批次已出栏（终态），不可再投产: " + batch.batchNo());
        }

        Instant producedAt = clock.instant();
        Optional<Long> productionIdOpt = productionRepository.insertProduction(
                batch.id(), formula.id(), formula.formulaCode(), formula.version(), producedAt);
        if (productionIdOpt.isEmpty()) {
            throw ApiException.conflict("该配方版本已投产到本批次，不可重复投产: "
                    + request.formulaCode() + " v" + request.version());
        }
        long productionId = productionIdOpt.get();

        boolean wasInProduction = FormulaStatus.IN_PRODUCTION.name().equals(formula.status());
        if (!wasInProduction) {
            formulaRepository.markFirstProduced(formula.id(), producedAt);
        }

        List<FormulaRepository.ComponentRow> drugRows =
                formulaRepository.findDrugs(formula.id());
        // 快照按投产时刻已批准版本内容冻结（含休药天数），后续版本修改不追溯减轻。
        Map<String, DictRepository.DrugDict> dict = new HashMap<>();
        for (FormulaRepository.ComponentRow d : drugRows) {
            DictRepository.DrugDict dObj = dictRepository.findDrug(d.code())
                    .orElseThrow(() -> ApiException.unprocessable("投产时药物字典缺失: " + d.code()));
            dict.put(d.code(), dObj);
            productionRepository.insertDrugSnapshot(productionId, d.code(), d.ratio(), dObj.withdrawalDays());
        }

        if (!drugRows.isEmpty()) {
            // 投产即发生一次含药饲喂（末次饲喂时刻由请求给出）。
            long feedingId = productionRepository.insertFeeding(
                    batch.id(), productionId, formula.formulaCode(), formula.version(),
                    request.feedAtUtc(), false);
            for (FormulaRepository.ComponentRow d : drugRows) {
                productionRepository.insertFeedingDrug(feedingId, d.code(), dict.get(d.code()).withdrawalDays());
            }
        }

        Merged merged = recomputeBatchWithdrawal(batch.id());
        batchRepository.updateWithdrawal(batch.id(),
                merged == null ? null : merged.latestFeed(),
                merged == null ? null : merged.untilDate(),
                merged == null ? null : merged.maxDays());

        BatchRepository.BatchRow refreshed = batchRepository.get(request.batchNo());
        return new ProductionResponse(
                request.formulaCode(),
                request.version(),
                request.batchNo(),
                producedAt,
                refreshed.lastMedicatedFeedAtUtc(),
                refreshed.withdrawalUntilDate(),
                earliest(refreshed.withdrawalUntilDate()),
                refreshed.governingWithdrawalDays());
    }

    /**
     * 登记一次含药饲喂事件（须已存在对应投产）；出栏后迟到则只记事件并告警。
     */
    @Transactional
    public FeedingRecordResponse recordFeeding(FeedingRecordRequest request) {
        FormulaRepository.FormulaRow formula =
                formulaRepository.get(request.formulaCode(), request.version());
        BatchRepository.BatchRow batch = batchRepository.lockAndGet(request.batchNo());
        ProductionRepository.ProductionRow production = productionRepository
                .findByBatchAndFormula(batch.id(), formula.id())
                .orElseThrow(() -> ApiException.unprocessable(
                        "该配方版本尚未投产到本批次，不能登记饲喂: "
                                + request.formulaCode() + " v" + request.version()));
        List<ProductionRepository.DrugSnapshotRow> snapshots =
                productionRepository.findSnapshots(production.id());
        if (snapshots.isEmpty()) {
            throw ApiException.unprocessable("该配方无药物添加剂，不属于含药饲喂: "
                    + request.formulaCode() + " v" + request.version());
        }

        boolean late = BatchStatus.SLAUGHTERED.name().equals(batch.status());
        long feedingId = productionRepository.insertFeeding(
                batch.id(), production.id(), formula.formulaCode(), formula.version(),
                request.feedAtUtc(), late);
        for (ProductionRepository.DrugSnapshotRow s : snapshots) {
            productionRepository.insertFeedingDrug(feedingId, s.drugCode(), s.withdrawalDays());
        }

        boolean alerted = false;
        if (late) {
            // 出栏终态不变；休药期字段也不因迟到饲喂而改变。
            alertRepository.insert(AlertType.LATE_MEDICATED_FEEDING.name(), batch.batchNo(), feedingId,
                    "批次已于 " + batch.slaughteredAtUtc() + " 出栏，出栏后收到迟到含药饲喂记录（时刻 "
                            + request.feedAtUtc() + "，配方 " + request.formulaCode()
                            + " v" + request.version() + "），仅记录事件并告警");
            alerted = true;
        } else {
            Merged merged = recomputeBatchWithdrawal(batch.id());
            batchRepository.updateWithdrawal(batch.id(),
                    merged == null ? null : merged.latestFeed(),
                    merged == null ? null : merged.untilDate(),
                    merged == null ? null : merged.maxDays());
        }

        return new FeedingRecordResponse(request.batchNo(), request.formulaCode(), request.version(),
                request.feedAtUtc(), late, alerted);
    }

    /**
     * 出栏申请（requestId 幂等）：休药期内拒绝并提示最早合法出栏时刻；
     * 重复出栏（终态）返回幂等成功；无休药义务的批次随时可出栏。
     */
    public IdempotencyService.Result<SlaughterResponse> applySlaughter(SlaughterRequest request) {
        IdempotencyService.Result<SlaughterResponse> result = idempotencyService.execute(
                request.requestId(),
                KIND_SLAUGHTER_APPLY,
                request,
                () -> doSlaughter(request),
                SlaughterResponse.class);
        if (result.replayed() && !result.response().replayed()) {
            SlaughterResponse r = result.response();
            return new IdempotencyService.Result<>(
                    new SlaughterResponse(r.batchNo(), r.approved(), true, r.status(),
                            r.earliestSlaughterUtc(), r.rejectReason(),
                            r.slaughteredAtUtc(), r.appliedAtUtc()),
                    result.httpStatus(), true);
        }
        return result;
    }

    private IdempotencyService.Outcome doSlaughter(SlaughterRequest request) {
        BatchRepository.BatchRow batch = batchRepository.lockAndGet(request.batchNo());
        Instant earliest = earliest(batch.withdrawalUntilDate());

        // 已出栏终态：重复出栏申请返回幂等成功。
        if (BatchStatus.SLAUGHTERED.name().equals(batch.status())) {
            // 台账登记该已批准申请（以实际出栏时刻为准），供后续治疗直报冲突判定。
            slaughterApplicationRepository.insertApproved(
                    request.requestId(), batch.id(), batch.batchNo(), batch.slaughteredAtUtc());
            return IdempotencyService.Outcome.ok(new SlaughterResponse(
                    batch.batchNo(), true, false, BatchStatus.SLAUGHTERED.name(),
                    earliest, null, batch.slaughteredAtUtc(), request.applyAtUtc()));
        }

        boolean approved;
        String rejectReason = null;
        Instant slaughteredAt = null;
        if (earliest != null && request.applyAtUtc().isBefore(earliest)) {
            // 恰满休药天数当日 24:00 前申请，一律拒绝。
            approved = false;
            rejectReason = "休药期未满，拒绝出栏；最早合法出栏时刻(UTC)=" + earliest
                    + "（届满日 " + batch.withdrawalUntilDate() + " 当日 24:00 之后）";
        } else {
            approved = true;
        }

        String status = BatchStatus.FEEDING.name();
        if (approved) {
            int updated = batchRepository.markSlaughteredIfFeeding(batch.id(), request.applyAtUtc());
            if (updated == 0) {
                throw ApiException.conflict("批次状态已被并发改变，请重新申请");
            }
            // 台账登记已批准申请；治疗直报重算后冲突时同事务撤销并保留记录。
            slaughterApplicationRepository.insertApproved(
                    request.requestId(), batch.id(), batch.batchNo(), request.applyAtUtc());
            slaughteredAt = request.applyAtUtc();
            status = BatchStatus.SLAUGHTERED.name();
        }

        SlaughterResponse response = new SlaughterResponse(
                batch.batchNo(), approved, false, status,
                earliest, rejectReason, slaughteredAt, request.applyAtUtc());
        // 休药期内拒绝为确定性的首次结果，以 422 落库，重放返回同一拒绝结果。
        return new IdempotencyService.Outcome(response, approved ? 200 : 422);
    }

    /**
     * 按批次全部含药饲喂事件逐支复算合并休药期：
     * 最晚末次饲喂时刻 + 各药物最大休药天数（仅统计非迟到事件；迟到事件不影响已冻结义务）。
     * 合并非迟到的治疗性用药直报：给药时刻参与最晚时刻合并，目录快照休药天数参与最大天数合并。
     */
    private Merged recomputeBatchWithdrawal(long batchId) {
        List<ProductionRepository.FeedingRow> feedings =
                productionRepository.findFeedingsByBatch(batchId);
        Instant latestFeed = null;
        int maxDays = 0;
        for (ProductionRepository.FeedingRow feeding : feedings) {
            if (feeding.late()) {
                continue;
            }
            List<ProductionRepository.DrugSnapshotRow> snapshots =
                    productionRepository.findSnapshots(feeding.productionId());
            int feedingMaxDays = snapshots.stream()
                    .mapToInt(ProductionRepository.DrugSnapshotRow::withdrawalDays)
                    .max().orElse(0);
            // 合并口径：各药物取最大休药天数；饲喂时刻取最晚一次。
            maxDays = Math.max(maxDays, feedingMaxDays);
            if (latestFeed == null || feeding.feedAtUtc().isAfter(latestFeed)) {
                latestFeed = feeding.feedAtUtc();
            }
        }
        // 治疗直报（非迟到）并入同一合并口径：最晚给药时刻 + 最大休药天数。
        for (TreatmentReportRepository.TreatmentRow treatment
                : treatmentReportRepository.findEffectiveByBatch(batchId)) {
            maxDays = Math.max(maxDays, treatment.withdrawalDays());
            if (latestFeed == null || treatment.administeredAtUtc().isAfter(latestFeed)) {
                latestFeed = treatment.administeredAtUtc();
            }
        }
        if (latestFeed == null) {
            return null;
        }
        LocalDate untilDate = WithdrawalCalculator.withdrawalUntilDate(latestFeed, maxDays);
        return new Merged(latestFeed, maxDays, untilDate);
    }

    private Instant earliest(LocalDate untilDate) {
        return untilDate == null ? null : WithdrawalCalculator.earliestSlaughter(untilDate);
    }

    private BatchStatusResponse toStatusResponse(BatchRepository.BatchRow row) {
        return new BatchStatusResponse(
                row.batchNo(),
                row.species(),
                row.status(),
                row.lastMedicatedFeedAtUtc(),
                row.withdrawalUntilDate(),
                earliest(row.withdrawalUntilDate()),
                row.governingWithdrawalDays(),
                row.slaughteredAtUtc());
    }

    /**
     * 治疗性用药直报（requestId 幂等）：
     * 两名不同养殖端人员确认方可生效；目录外药品拒绝受理且不改变原出栏日；
     * 受理后按最大休药天数与最晚给药时刻重算合并休药期，原最早出栏日作废；
     * 已批准且与新最早出栏日冲突的出栏申请在同一事务内撤销并保留撤销记录；
     * 出栏核销终态后的迟到直报只记事件并告警，不改变终态。
     */
    public IdempotencyService.Result<TreatmentReportResponse> reportTreatment(TreatmentReportRequest request) {
        IdempotencyService.Result<TreatmentReportResponse> result = idempotencyService.execute(
                request.requestId(),
                KIND_TREATMENT_REPORT,
                request,
                () -> doReportTreatment(request),
                TreatmentReportResponse.class);
        if (result.replayed() && !result.response().replayed()) {
            TreatmentReportResponse r = result.response();
            return new IdempotencyService.Result<>(
                    new TreatmentReportResponse(r.requestId(), r.batchNo(), r.accepted(), r.rejectReason(),
                            r.late(), r.alerted(), true, r.drugName(), r.withdrawalDays(),
                            r.administeredAtUtc(), r.withdrawalUntilDate(), r.earliestSlaughterUtc(),
                            r.governingWithdrawalDays(), r.revokedSlaughterRequestIds()),
                    result.httpStatus(), true);
        }
        return result;
    }

    private IdempotencyService.Outcome doReportTreatment(TreatmentReportRequest request) {
        // 行锁串行化同一批次的并发直报/饲喂/出栏。
        BatchRepository.BatchRow batch = batchRepository.lockAndGet(request.batchNo());

        // 双人确认：恰好两名不同养殖端人员；单人直报不生效且不改变任何状态。
        List<String> confirmers = request.confirmedBy() == null ? List.of() : request.confirmedBy();
        long distinctConfirmers = confirmers.stream()
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .count();
        if (confirmers.size() != 2 || distinctConfirmers != 2) {
            return new IdempotencyService.Outcome(
                    rejectedTreatment(request, "治疗直报须两名不同养殖端人员确认，单人直报不生效"), 422);
        }

        // 目录外药品拒绝受理，且不改变原出栏日。
        Optional<DictRepository.VetDrugDict> drugOpt = dictRepository.findVetDrugByName(request.drugName());
        if (drugOpt.isEmpty()) {
            return new IdempotencyService.Outcome(
                    rejectedTreatment(request, "兽药目录外的药品名称，拒绝受理: " + request.drugName()), 422);
        }
        DictRepository.VetDrugDict drug = drugOpt.get();

        Instant now = clock.instant();
        boolean slaughtered = BatchStatus.SLAUGHTERED.name().equals(batch.status());
        // 出栏核销终态（计划出栏时刻已到）后迟到的直报：只记事件并告警，终态语义不变。
        boolean late = slaughtered && batch.slaughteredAtUtc() != null
                && !batch.slaughteredAtUtc().isAfter(now);

        long reportId;
        try {
            reportId = treatmentReportRepository.insert(
                    request.requestId(), batch.id(), batch.batchNo(), drug.code(), drug.name(),
                    drug.withdrawalDays(), request.administeredAtUtc(),
                    confirmers.get(0), confirmers.get(1), late, FingerprintUtil.sha256(request));
        } catch (DuplicateKeyException e) {
            // 同一批次同一给药时刻只允许一条进行中直报。
            throw ApiException.conflict("同一批次同一给药时刻已存在进行中直报: "
                    + request.batchNo() + " " + request.administeredAtUtc());
        }

        if (late) {
            alertRepository.insert(AlertType.LATE_TREATMENT_REPORT.name(), batch.batchNo(), null,
                    "批次已于 " + batch.slaughteredAtUtc() + " 出栏核销（终态），出栏后收到迟到治疗用药直报（药品 "
                            + request.drugName() + "，给药时刻 " + request.administeredAtUtc()
                            + "，直报编号 " + reportId + "），仅记录事件并告警，不改变出栏终态");
            return IdempotencyService.Outcome.ok(new TreatmentReportResponse(
                    request.requestId(), batch.batchNo(), true, null, true, true, false,
                    drug.name(), drug.withdrawalDays(), request.administeredAtUtc(),
                    batch.withdrawalUntilDate(), earliest(batch.withdrawalUntilDate()),
                    batch.governingWithdrawalDays(), List.of()));
        }

        // 重算合并休药期（含本次直报）：原最早出栏日作废。
        Merged merged = recomputeBatchWithdrawal(batch.id());
        batchRepository.updateWithdrawal(batch.id(),
                merged == null ? null : merged.latestFeed(),
                merged == null ? null : merged.untilDate(),
                merged == null ? null : merged.maxDays());
        Instant newEarliest = merged == null ? null
                : WithdrawalCalculator.earliestSlaughter(merged.untilDate());

        // 已批准但计划出栏时刻尚在未来的申请：与新最早出栏日冲突时在同一事务内撤销。
        List<String> revokedRequestIds = new ArrayList<>();
        if (slaughtered && newEarliest != null) {
            List<SlaughterApplicationRepository.ApplicationRow> approved =
                    slaughterApplicationRepository.findApprovedByBatch(batch.id());
            for (SlaughterApplicationRepository.ApplicationRow application : approved) {
                if (application.applyAtUtc().isBefore(newEarliest)) {
                    slaughterApplicationRepository.markRevoked(application.id());
                    slaughterApplicationRepository.insertRevocation(
                            application.id(), application.requestId(), batch.id(), batch.batchNo(),
                            application.applyAtUtc(), newEarliest, reportId,
                            "治疗用药直报触发休药期重算，新最早合法出栏时刻 " + newEarliest
                                    + " 晚于已批准出栏时刻 " + application.applyAtUtc() + "，自动撤销该申请");
                    revokedRequestIds.add(application.requestId());
                }
            }
            if (!revokedRequestIds.isEmpty()) {
                // 撤销后批次恢复饲养中，等待新休药期届满后重新申请出栏。
                batchRepository.clearSlaughter(batch.id());
            }
        }

        return IdempotencyService.Outcome.ok(new TreatmentReportResponse(
                request.requestId(), batch.batchNo(), true, null, false, false, false,
                drug.name(), drug.withdrawalDays(), request.administeredAtUtc(),
                merged == null ? null : merged.untilDate(), newEarliest,
                merged == null ? null : merged.maxDays(), List.copyOf(revokedRequestIds)));
    }

    /**
     * 查询批次出栏申请撤销记录（撤销审计，保留可查）。
     */
    @Transactional(readOnly = true)
    public List<SlaughterRevocationResponse> listSlaughterRevocations(String batchNo) {
        if (!batchRepository.exists(batchNo)) {
            throw ApiException.notFound("养殖批次不存在: " + batchNo);
        }
        return slaughterApplicationRepository.findRevocationsByBatchNo(batchNo);
    }

    private TreatmentReportResponse rejectedTreatment(TreatmentReportRequest request, String reason) {
        return new TreatmentReportResponse(
                request.requestId(), request.batchNo(), false, reason, false, false, false,
                null, null, request.administeredAtUtc(),
                null, null, null, List.of());
    }

    /** 合并休药期中间结果。 */
    private record Merged(Instant latestFeed, int maxDays, LocalDate untilDate) {
    }
}
