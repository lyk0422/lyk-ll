package com.example.starter.dye.service;

import com.example.starter.dye.domain.Disposition;
import com.example.starter.dye.domain.LotJudgementPO;
import com.example.starter.dye.domain.OperationType;
import com.example.starter.dye.domain.RunStatus;
import com.example.starter.dye.domain.VatRunPO;
import com.example.starter.dye.repo.DyeVatRepository;
import com.example.starter.dye.repo.LateColorEventRepository;
import com.example.starter.dye.repo.LotJudgementRepository;
import com.example.starter.dye.repo.VatRunRepository;
import com.example.starter.dye.web.BusinessRuleException;
import com.example.starter.dye.web.dto.ArchiveRequest;
import com.example.starter.dye.web.dto.DischargeMeasureRequest;
import com.example.starter.dye.web.dto.JudgeRequest;
import com.example.starter.dye.web.dto.JudgeResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 出缸测色、缸差判定与归档状态机。
 *
 * <pre>
 * PLANNED --投料--> RUNNING --测色--> DISCHARGED --判定--+-- 合格 PASS --------------> 可归档
 *                                                       +-- 超差首次 REPAIR -> REPAIRED --复检测色--> DISCHARGED
 *                                                            --复检仍超差 GRADED_DOWN ----------------> 可归档
 * 归档 ARCHIVED 为终态；归档后迟到测色只登记 late_color_event。
 * </pre>
 *
 * <p>缸差门限：同配方两缸出缸 ΔE 差值 |ΔE1-ΔE2| &gt; 0.5 须回修，恰 0.5 合格；
 * 回修仅允许一次，回修后仍超差转降级。无对照缸且无同配方历史缸时，以标准样为基准按
 * 本缸出缸 ΔE ≤ 0.8（对色门限）判定。</p>
 */
@Service
public class DischargeJudgeService {

    /** 缸差回修门限：差值超过 0.5（不含边界） */
    public static final BigDecimal LOT_GAP_LIMIT = new BigDecimal("0.5");

    private final IdempotencyService idempotency;
    private final VatRunRepository vatRunRepository;
    private final LotJudgementRepository lotJudgementRepository;
    private final LateColorEventRepository lateColorEventRepository;
    private final DyeVatRepository dyeVatRepository;
    private final Clock clock;

    public DischargeJudgeService(IdempotencyService idempotency, VatRunRepository vatRunRepository,
                                 LotJudgementRepository lotJudgementRepository,
                                 LateColorEventRepository lateColorEventRepository,
                                 DyeVatRepository dyeVatRepository, Clock clock) {
        this.idempotency = idempotency;
        this.vatRunRepository = vatRunRepository;
        this.lotJudgementRepository = lotJudgementRepository;
        this.lateColorEventRepository = lateColorEventRepository;
        this.dyeVatRepository = dyeVatRepository;
        this.clock = clock;
    }

    /**
     * 出缸测色上报。非幂等操作：归档后到达只记迟到事件。
     */
    @Transactional
    public VatRunMeasureView measure(DischargeMeasureRequest request) {
        VatRunPO run = vatRunRepository.findByNoForUpdate(request.runNo())
                .orElseThrow(() -> new BusinessRuleException("缸次不存在: " + request.runNo()));
        if (run.getStatus() == RunStatus.ARCHIVED) {
            lateColorEventRepository.insert(run.getId(), run.getRunNo(), request.deltaE(),
                    LocalDateTime.now(clock));
            return new VatRunMeasureView(run.getRunNo(), request.deltaE(), RunStatus.ARCHIVED.name(), true);
        }
        if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.REPAIRED) {
            throw new BusinessRuleException("缸次 " + request.runNo() + " 当前状态 " + run.getStatus()
                    + "，仅执行中(RUNNING)或回修复检(REPAIRED)缸次可上报出缸测色");
        }
        vatRunRepository.updateOutDeltaE(run.getId(), request.deltaE());
        // 回修复检重新进入待判，清空上一次处置结论；回修次数保留
        vatRunRepository.updateJudgement(run.getId(), RunStatus.DISCHARGED, null,
                run.getRepairCount(), LocalDateTime.now(clock));
        return new VatRunMeasureView(run.getRunNo(), request.deltaE(), RunStatus.DISCHARGED.name(), false);
    }

    /**
     * 缸差判定（requestId 幂等）。
     */
    @Transactional
    public IdempotencyService.Outcome<JudgeResponse> judge(JudgeRequest request) {
        return idempotency.execute(OperationType.JUDGE, request.requestId(), request, () -> {
            VatRunPO run = vatRunRepository.findByNoForUpdate(request.runNo())
                    .orElseThrow(() -> new BusinessRuleException("缸次不存在: " + request.runNo()));
            if (run.getStatus() == RunStatus.ARCHIVED) {
                throw new BusinessRuleException("缸次已归档终态，不能再判定: " + request.runNo());
            }
            if (run.getStatus() != RunStatus.DISCHARGED || run.getOutDeltaE() == null) {
                throw new BusinessRuleException("缸次 " + request.runNo() + " 当前状态 " + run.getStatus()
                        + "，须先出缸测色进入待判(DISCHARGED)状态");
            }
            if (run.getFinalDisposition() == Disposition.PASS
                    || run.getFinalDisposition() == Disposition.GRADED_DOWN) {
                throw new BusinessRuleException("缸次已有终局处置(" + run.getFinalDisposition()
                        + ")，请直接归档，不能重复判定");
            }

            Baseline baseline = resolveBaseline(run, request.baselineRunNo());
            BigDecimal gap = baseline == null
                    ? run.getOutDeltaE()
                    : run.getOutDeltaE().subtract(baseline.deltaE()).abs();
            BigDecimal limit = baseline == null ? ColorMatchService.PASS_THRESHOLD : LOT_GAP_LIMIT;
            boolean within = gap.compareTo(limit) <= 0;

            Disposition disposition;
            RunStatus newStatus;
            int repairCount = run.getRepairCount();
            if (within) {
                disposition = Disposition.PASS;
                newStatus = RunStatus.DISCHARGED;
            } else if (repairCount == 0) {
                disposition = Disposition.REPAIR;
                newStatus = RunStatus.REPAIRED;
                repairCount = 1;
            } else {
                disposition = Disposition.GRADED_DOWN;
                newStatus = RunStatus.GRADED_DOWN;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            vatRunRepository.updateJudgement(run.getId(), newStatus, disposition, repairCount, now);
            String baselineNo = baseline == null ? null : baseline.runNo();
            lotJudgementRepository.insert(buildJudgement(run, baselineNo, gap, disposition.name(),
                    repairCount, request.requestId()), now);

            JudgeResponse body = new JudgeResponse(request.requestId(), run.getRunNo(), baselineNo, gap,
                    disposition.name(), repairCount, newStatus.name());
            return IdempotencyService.ServiceResult.ok(body);
        });
    }

    /**
     * 判定归档（requestId 幂等）。归档为终态；对已归档缸次重复归档（含换新 requestId）返回成功。
     */
    @Transactional
    public IdempotencyService.Outcome<JudgeResponse> archive(ArchiveRequest request) {
        return idempotency.execute(OperationType.ARCHIVE, request.requestId(), request, () -> {
            VatRunPO run = vatRunRepository.findByNoForUpdate(request.runNo())
                    .orElseThrow(() -> new BusinessRuleException("缸次不存在: " + request.runNo()));
            if (run.getStatus() == RunStatus.ARCHIVED) {
                // 终态重复归档：幂等成功，返回终态快照，不再写台账
                return IdempotencyService.ServiceResult.ok(archivedSnapshot(request.requestId(), run));
            }
            if (run.getFinalDisposition() != Disposition.PASS
                    && run.getFinalDisposition() != Disposition.GRADED_DOWN) {
                throw new BusinessRuleException("缸次 " + request.runNo() + " 尚未形成合格或降级终局处置（当前 "
                        + run.getStatus() + "/" + run.getFinalDisposition() + "），不能归档");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            vatRunRepository.updateStatus(run.getId(), RunStatus.ARCHIVED);
            dyeVatRepository.release(run.getVatId(), run.getId());
            lotJudgementRepository.insert(buildJudgement(run, null, run.getOutDeltaE(),
                    "ARCHIVED", run.getRepairCount(), request.requestId()), now);
            return IdempotencyService.ServiceResult.ok(
                    new JudgeResponse(request.requestId(), run.getRunNo(), null, run.getOutDeltaE(),
                            "ARCHIVED", run.getRepairCount(), RunStatus.ARCHIVED.name()));
        });
    }

    private JudgeResponse archivedSnapshot(String requestId, VatRunPO run) {
        return new JudgeResponse(requestId, run.getRunNo(), null, run.getOutDeltaE(), "ARCHIVED",
                run.getRepairCount(), RunStatus.ARCHIVED.name());
    }

    private Baseline resolveBaseline(VatRunPO run, String explicitBaselineNo) {
        if (explicitBaselineNo != null && !explicitBaselineNo.isBlank()) {
            VatRunPO baseline = vatRunRepository.findByNo(explicitBaselineNo)
                    .orElseThrow(() -> new BusinessRuleException("对照缸次不存在: " + explicitBaselineNo));
            if (!baseline.getRecipeCode().equals(run.getRecipeCode())) {
                throw new BusinessRuleException("缸差对照缸配方不同（" + baseline.getRecipeCode() + " vs "
                        + run.getRecipeCode() + "），拒绝跨配方比对");
            }
            if (baseline.getOutDeltaE() == null) {
                throw new BusinessRuleException("对照缸次尚未出缸测色: " + explicitBaselineNo);
            }
            return new Baseline(baseline.getRunNo(), baseline.getOutDeltaE());
        }
        // 自动选取同配方、本缸之前最近一缸已测色缸次作为基准
        List<VatRunPO> sameRecipe = vatRunRepository.findSameRecipe(run.getRecipeCode()).stream()
                .filter(r -> !r.getId().equals(run.getId()))
                .filter(r -> r.getOutDeltaE() != null)
                .sorted(Comparator.comparing(VatRunPO::getId).reversed())
                .toList();
        return sameRecipe.isEmpty() ? null
                : new Baseline(sameRecipe.get(0).getRunNo(), sameRecipe.get(0).getOutDeltaE());
    }

    private LotJudgementPO buildJudgement(VatRunPO run, String baselineNo, BigDecimal gap,
                                          String result, int repairCount, String requestId) {
        LotJudgementPO po = new LotJudgementPO();
        po.setRunId(run.getId());
        po.setRunNo(run.getRunNo());
        po.setRecipeCode(run.getRecipeCode());
        po.setBaselineRunNo(baselineNo);
        po.setDeltaEGap(gap);
        po.setResult(result);
        po.setRepairCount(repairCount);
        po.setRequestId(requestId);
        return po;
    }

    private record Baseline(String runNo, BigDecimal deltaE) {
    }

    /**
     * 测色上报结果视图；lateEvent=true 表示缸次已归档，数据仅登记为迟到事件。
     */
    public record VatRunMeasureView(String runNo, BigDecimal deltaE, String status, boolean lateEvent) {
    }
}
