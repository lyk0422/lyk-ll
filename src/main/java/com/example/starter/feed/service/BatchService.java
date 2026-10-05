package com.example.starter.feed.service;

import com.example.starter.feed.domain.AlertType;
import com.example.starter.feed.domain.BatchStatus;
import com.example.starter.feed.domain.FormulaStatus;
import com.example.starter.feed.domain.SlaughterApplicationStatus;
import com.example.starter.feed.dto.BatchCreateRequest;
import com.example.starter.feed.dto.BatchStatusResponse;
import com.example.starter.feed.dto.FeedingRecordRequest;
import com.example.starter.feed.dto.FeedingRecordResponse;
import com.example.starter.feed.dto.ProductionRequest;
import com.example.starter.feed.dto.ProductionResponse;
import com.example.starter.feed.dto.SlaughterApplicationTrailResponse;
import com.example.starter.feed.dto.SlaughterApplicationView;
import com.example.starter.feed.dto.SlaughterRequest;
import com.example.starter.feed.dto.SlaughterResponse;
import com.example.starter.feed.repo.AlertRepository;
import com.example.starter.feed.repo.BatchRepository;
import com.example.starter.feed.repo.DictRepository;
import com.example.starter.feed.repo.FormulaRepository;
import com.example.starter.feed.repo.ProductionRepository;
import com.example.starter.feed.repo.SlaughterApplicationRepository;
import com.example.starter.feed.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 养殖批次：登记、含药配方投产与休药期合并、含药饲喂（含迟到告警）、出栏判定。
 * 出栏申请逐条落账：未来时刻批准为 SCHEDULED 预约（可被治疗直报重算撤销），
 * 非未来时刻批准即核销出栏（终态）。
 */
@Service
public class BatchService {

    /** 幂等请求类型：出栏申请。 */
    public static final String KIND_SLAUGHTER_APPLY = "SLAUGHTER_APPLY";

    private final BatchRepository batchRepository;
    private final FormulaRepository formulaRepository;
    private final ProductionRepository productionRepository;
    private final DictRepository dictRepository;
    private final AlertRepository alertRepository;
    private final SlaughterApplicationRepository slaughterApplicationRepository;
    private final IdempotencyService idempotencyService;
    private final WithdrawalRecalcService withdrawalRecalcService;
    private final Clock clock;

    public BatchService(BatchRepository batchRepository,
                        FormulaRepository formulaRepository,
                        ProductionRepository productionRepository,
                        DictRepository dictRepository,
                        AlertRepository alertRepository,
                        SlaughterApplicationRepository slaughterApplicationRepository,
                        IdempotencyService idempotencyService,
                        WithdrawalRecalcService withdrawalRecalcService,
                        Clock clock) {
        this.batchRepository = batchRepository;
        this.formulaRepository = formulaRepository;
        this.productionRepository = productionRepository;
        this.dictRepository = dictRepository;
        this.alertRepository = alertRepository;
        this.slaughterApplicationRepository = slaughterApplicationRepository;
        this.idempotencyService = idempotencyService;
        this.withdrawalRecalcService = withdrawalRecalcService;
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

        WithdrawalCalculator.MergedWithdrawal merged =
                withdrawalRecalcService.recomputeAndApply(batch.id());

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
            withdrawalRecalcService.recomputeAndApply(batch.id());
        }

        return new FeedingRecordResponse(request.batchNo(), request.formulaCode(), request.version(),
                request.feedAtUtc(), late, alerted);
    }

    /**
     * 出栏申请（requestId 幂等）：休药期内拒绝并提示最早合法出栏时刻；
     * 重复出栏（终态）返回幂等成功；无休药义务的批次随时可出栏。
     * 申请时刻在未来时批准为 SCHEDULED 预约（批次保持饲养中，可被治疗直报重算撤销）；
     * 非未来时刻批准即核销出栏（终态）。每次申请均落台账，撤销记录保留可查。
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
            slaughterApplicationRepository.insert(request.requestId(), batch.id(), request.applyAtUtc(),
                    true, SlaughterApplicationStatus.EXECUTED.name(), null);
            return IdempotencyService.Outcome.ok(new SlaughterResponse(
                    batch.batchNo(), true, false, BatchStatus.SLAUGHTERED.name(),
                    earliest, null, batch.slaughteredAtUtc(), request.applyAtUtc()));
        }

        if (earliest != null && request.applyAtUtc().isBefore(earliest)) {
            // 恰满休药天数当日 24:00 前申请，一律拒绝。
            String rejectReason = "休药期未满，拒绝出栏；最早合法出栏时刻(UTC)=" + earliest
                    + "（届满日 " + batch.withdrawalUntilDate() + " 当日 24:00 之后）";
            slaughterApplicationRepository.insert(request.requestId(), batch.id(), request.applyAtUtc(),
                    false, SlaughterApplicationStatus.REJECTED.name(), rejectReason);
            SlaughterResponse rejected = new SlaughterResponse(
                    batch.batchNo(), false, false, BatchStatus.FEEDING.name(),
                    earliest, rejectReason, null, request.applyAtUtc());
            // 休药期内拒绝为确定性的首次结果，以 422 落库，重放返回同一拒绝结果。
            return new IdempotencyService.Outcome(rejected, 422);
        }

        // 申请时刻在未来：批准为预约申请，批次保持饲养中，暂不核销；
        // 后续治疗直报重算若与新最早出栏日冲突将自动撤销该预约。
        if (request.applyAtUtc().isAfter(clock.instant())) {
            slaughterApplicationRepository.insert(request.requestId(), batch.id(), request.applyAtUtc(),
                    true, SlaughterApplicationStatus.SCHEDULED.name(), null);
            return IdempotencyService.Outcome.ok(new SlaughterResponse(
                    batch.batchNo(), true, false, BatchStatus.FEEDING.name(),
                    earliest, null, null, request.applyAtUtc()));
        }

        int updated = batchRepository.markSlaughteredIfFeeding(batch.id(), request.applyAtUtc());
        if (updated == 0) {
            throw ApiException.conflict("批次状态已被并发改变，请重新申请");
        }
        slaughterApplicationRepository.insert(request.requestId(), batch.id(), request.applyAtUtc(),
                true, SlaughterApplicationStatus.EXECUTED.name(), null);
        return IdempotencyService.Outcome.ok(new SlaughterResponse(
                batch.batchNo(), true, false, BatchStatus.SLAUGHTERED.name(),
                earliest, null, request.applyAtUtc(), request.applyAtUtc()));
    }

    /**
     * 查询批次出栏申请台账（含 REVOKED 自动撤销记录，保留可查）。
     */
    @Transactional(readOnly = true)
    public SlaughterApplicationTrailResponse listSlaughterApplications(String batchNo) {
        BatchRepository.BatchRow batch = batchRepository.get(batchNo);
        List<SlaughterApplicationView> applications = slaughterApplicationRepository
                .findByBatch(batch.id()).stream()
                .map(row -> new SlaughterApplicationView(
                        row.id(), row.requestId(), row.applyAtUtc(), row.approved(), row.status(),
                        row.rejectReason(), row.revokedAtUtc(), row.revokeReason()))
                .toList();
        return new SlaughterApplicationTrailResponse(batch.batchNo(), applications);
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
}
