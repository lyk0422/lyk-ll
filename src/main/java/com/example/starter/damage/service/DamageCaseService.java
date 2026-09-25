package com.example.starter.damage.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.api.CaseStatus;
import com.example.starter.damage.api.EstimateDecision;
import com.example.starter.damage.api.dto.ApproveRepairRequest;
import com.example.starter.damage.api.dto.CaseViews.CaseResponse;
import com.example.starter.damage.api.dto.CaseViews.DamageItemView;
import com.example.starter.damage.api.dto.CaseViews.EstimateView;
import com.example.starter.damage.api.dto.CaseViews.EventView;
import com.example.starter.damage.api.dto.CaseViews.RegisterResponse;
import com.example.starter.damage.api.dto.CaseViews.RepairCycleView;
import com.example.starter.damage.api.dto.CompleteRepairRequest;
import com.example.starter.damage.api.dto.EstimateRequest;
import com.example.starter.damage.api.dto.RegisterRequest;
import com.example.starter.damage.api.dto.ReinspectRequest;
import com.example.starter.damage.api.dto.ReleaseRequest;
import com.example.starter.damage.api.dto.SurveyItemDto;
import com.example.starter.damage.api.dto.SurveyRequest;
import com.example.starter.damage.repo.CaseEventRepository;
import com.example.starter.damage.repo.DamageCaseRepository;
import com.example.starter.damage.repo.DamageItemRepository;
import com.example.starter.damage.repo.EstimateRepository;
import com.example.starter.damage.repo.IiclCodeRepository;
import com.example.starter.damage.repo.RepairCycleRepository;
import com.example.starter.damage.repo.po.DamageCasePo;
import com.example.starter.damage.repo.po.DamageItemPo;
import com.example.starter.damage.repo.po.EstimateVersionPo;
import com.example.starter.damage.repo.po.IiclCodePo;
import com.example.starter.damage.repo.po.RepairCyclePo;
import com.example.starter.damage.service.support.EstimateCalculator;
import com.example.starter.damage.service.support.Fingerprints;
import com.example.starter.damage.service.support.IdempotencyGate;
import com.example.starter.damage.service.support.TimeWindows;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 残损案流转服务：登记→检验→估价→批复→完工→复检→放行/全损。 */
@Service
public class DamageCaseService {

    private final DamageCaseRepository caseRepository;
    private final DamageItemRepository itemRepository;
    private final IiclCodeRepository iiclCodeRepository;
    private final EstimateRepository estimateRepository;
    private final RepairCycleRepository cycleRepository;
    private final CaseEventRepository eventRepository;
    private final IdempotencyGate idempotencyGate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate requiresNewTx;

    public DamageCaseService(DamageCaseRepository caseRepository,
                             DamageItemRepository itemRepository,
                             IiclCodeRepository iiclCodeRepository,
                             EstimateRepository estimateRepository,
                             RepairCycleRepository cycleRepository,
                             CaseEventRepository eventRepository,
                             IdempotencyGate idempotencyGate,
                             ObjectMapper objectMapper,
                             Clock clock,
                             PlatformTransactionManager transactionManager) {
        this.caseRepository = caseRepository;
        this.itemRepository = itemRepository;
        this.iiclCodeRepository = iiclCodeRepository;
        this.estimateRepository = estimateRepository;
        this.cycleRepository = cycleRepository;
        this.eventRepository = eventRepository;
        this.idempotencyGate = idempotencyGate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String fingerprint = fingerprint("REGISTER", request);
        return idempotencyGate.run("REGISTER", request.requestId(), fingerprint, 201,
                RegisterResponse.class, () -> doRegister(request));
    }

    private RegisterResponse doRegister(RegisterRequest request) {
        long now = now(request.arrivedAtUtc());
        caseRepository.findActiveByContainer(request.containerNo()).ifPresent(c -> {
            throw ApiException.conflict("ACTIVE_CASE_EXISTS",
                    "箱号 " + request.containerNo() + " 已存在进行中残损案 " + c.caseNo());
        });
        String caseNo = "DC-" + now + "-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long caseId;
        try {
            caseId = caseRepository.insert(caseNo, request.containerNo(),
                    request.containerValue(), request.containerNo(), now);
        } catch (DuplicateKeyException concurrentRegistration) {
            // active_flag 唯一约束仲裁并发登记：胜方可能尚未提交，不依赖查询可见性，直接拒绝负方。
            throw ApiException.conflict("ACTIVE_CASE_EXISTS",
                    "箱号 " + request.containerNo() + " 并发登记，仅唯一受理一个进行中残损案");
        }
        eventRepository.insert(caseId, "REGISTERED", request.containerNo(), now);
        return new RegisterResponse(caseNo, request.containerNo(),
                CaseStatus.REGISTERED.name(), now);
    }

    @Transactional
    public CaseResponse survey(SurveyRequest request) {
        String fingerprint = fingerprint("SURVEY", request);
        return idempotencyGate.run("SURVEY", request.requestId(), fingerprint, 200,
                CaseResponse.class, () -> doSurvey(request));
    }

    private CaseResponse doSurvey(SurveyRequest request) {
        DamageCasePo dc = requireCase(request.caseNo());
        long now = now(request.surveyedAtUtc());
        if (isClosed(dc)) {
            // 结案后迟到检验照片：只记迟到事件，不改变终态。
            long surveyId = itemRepository.insertSurvey(dc.id(), request.requestId(),
                    request.surveyor(), now, true, clock.millis());
            eventRepository.insert(dc.id(), "LATE_SURVEY",
                    "结案后迟到检验 surveyId=" + surveyId + " 项数=" + request.items().size(),
                    clock.millis());
            return buildView(dc);
        }
        if (!CaseStatus.REGISTERED.name().equals(dc.status())) {
            throw ApiException.unprocessable("INVALID_STATE",
                    "案件状态 " + dc.status() + " 不允许检验登记");
        }
        Map<String, IiclCodePo> codes = resolveCodes(request.items());
        long surveyId = itemRepository.insertSurvey(dc.id(), request.requestId(),
                request.surveyor(), now, false, clock.millis());
        for (SurveyItemDto item : request.items()) {
            IiclCodePo code = codes.get(item.code());
            BigDecimal amount = code.unitCost().multiply(BigDecimal.valueOf(item.quantity()))
                    .setScale(2);
            itemRepository.insertDamageItem(dc.id(), surveyId, item.code(), item.location(),
                    item.responsibility().name(), code.repairCode(), code.repairName(),
                    code.unitCost(), item.quantity(), amount, clock.millis());
        }
        caseRepository.updateStatus(dc.id(), CaseStatus.SURVEYED.name(), clock.millis());
        eventRepository.insert(dc.id(), "SURVEYED",
                "残损项 " + request.items().size() + " 条", clock.millis());
        return buildView(requireCase(request.caseNo()));
    }

    @Transactional
    public CaseResponse estimate(EstimateRequest request) {
        String fingerprint = fingerprint("ESTIMATE", request);
        return idempotencyGate.run("ESTIMATE", request.requestId(), fingerprint, 200,
                CaseResponse.class, () -> doEstimate(request));
    }

    private CaseResponse doEstimate(EstimateRequest request) {
        DamageCasePo dc = requireCase(request.caseNo());
        long now = clock.millis();
        boolean firstEstimate = CaseStatus.SURVEYED.name().equals(dc.status());
        boolean reEstimateAfterExpiry = CaseStatus.REPAIR_APPROVED.name().equals(dc.status())
                && latestCycleExpired(dc);
        if (!firstEstimate && !reEstimateAfterExpiry) {
            throw ApiException.unprocessable("INVALID_STATE",
                    "案件状态 " + dc.status() + " 不允许估价");
        }
        List<DamageItemPo> items = itemRepository.findByCaseId(dc.id());
        if (items.isEmpty()) {
            throw ApiException.unprocessable("NO_DAMAGE_ITEM", "残损清单为空，无法估价");
        }
        EstimateCalculator.Result calc = EstimateCalculator.calculate(items, dc.containerValue());
        int versionNo = estimateRepository.countByCaseId(dc.id()) + 1;
        long estimateId = estimateRepository.insertVersion(dc.id(), versionNo, request.requestId(),
                calc.subtotalOwner(), calc.subtotalTerminal(), calc.totalAmount(),
                calc.maxItemAmount(), calc.ratio(), calc.decision().name(), now);
        for (DamageItemPo item : items) {
            estimateRepository.insertEstimateItem(estimateId, item.id(), item.amount());
        }
        if (calc.decision() == EstimateDecision.TOTAL_LOSS) {
            // 全损唯一判定成立：直接结案，全损与修理互斥。
            caseRepository.updateCurrentEstimate(dc.id(), estimateId,
                    CaseStatus.TOTAL_LOSS.name(), now);
            caseRepository.close(dc.id(), CaseStatus.TOTAL_LOSS.name(),
                    CaseStatus.TOTAL_LOSS.name(), now);
            eventRepository.insert(dc.id(), "TOTAL_LOSS_DECLARED",
                    "估价版本 v" + versionNo + " 单项 " + calc.maxItemAmount()
                            + " 超过箱值 30%", now);
        } else {
            caseRepository.updateCurrentEstimate(dc.id(), estimateId,
                    CaseStatus.ESTIMATED.name(), now);
            eventRepository.insert(dc.id(), "ESTIMATED",
                    "估价版本 v" + versionNo + " 判定修理", now);
        }
        return buildView(requireCase(request.caseNo()));
    }

    @Transactional
    public CaseResponse approveRepair(ApproveRepairRequest request) {
        String fingerprint = fingerprint("APPROVE_REPAIR", request);
        return idempotencyGate.run("APPROVE_REPAIR", request.requestId(), fingerprint, 200,
                CaseResponse.class, () -> doApproveRepair(request));
    }

    private CaseResponse doApproveRepair(ApproveRepairRequest request) {
        DamageCasePo dc = requireCase(request.caseNo());
        long approvedAt = now(request.approvedAtUtc());
        // 全损与修理互斥：终态案件不得再批复修理。
        if (CaseStatus.TOTAL_LOSS.name().equals(dc.status())) {
            throw ApiException.unprocessable("REPAIR_NOT_ALLOWED",
                    "估价结论为全损，修理与全损不得并发成立");
        }
        if (CaseStatus.RELEASED.name().equals(dc.status())) {
            throw ApiException.conflict("CASE_ALREADY_CLOSED",
                    "案件已放行结案，不得再批复修理");
        }
        boolean firstApproval = CaseStatus.ESTIMATED.name().equals(dc.status());
        boolean reworkApproval = CaseStatus.REWORK_REQUIRED.name().equals(dc.status());
        if (!firstApproval && !reworkApproval) {
            throw ApiException.unprocessable("INVALID_STATE",
                    "案件状态 " + dc.status() + " 不允许修理批复");
        }
        EstimateVersionPo estimate = currentEstimate(dc);
        if (estimate == null || EstimateDecision.TOTAL_LOSS.name().equals(estimate.decision())) {
            throw ApiException.unprocessable("REPAIR_NOT_ALLOWED",
                    "估价结论为全损，修理与全损不得并发成立");
        }
        int attemptNo = cycleRepository.findByCaseId(dc.id()).size() + 1;
        long validUntil = TimeWindows.approvalValidUntilUtc(approvedAt);
        cycleRepository.insertApproved(dc.id(), attemptNo, estimate.id(), request.requestId(),
                approvedAt, validUntil, clock.millis());
        caseRepository.updateApproval(dc.id(), approvedAt, validUntil, attemptNo,
                CaseStatus.REPAIR_APPROVED.name(), clock.millis());
        eventRepository.insert(dc.id(), "REPAIR_APPROVED",
                "第 " + attemptNo + " 次修理批复，有效期至 UTC " + validUntil, clock.millis());
        return buildView(requireCase(request.caseNo()));
    }

    @Transactional
    public CaseResponse completeRepair(CompleteRepairRequest request) {
        String fingerprint = fingerprint("COMPLETE_REPAIR", request);
        return idempotencyGate.run("COMPLETE_REPAIR", request.requestId(), fingerprint, 200,
                CaseResponse.class, () -> doCompleteRepair(request));
    }

    private CaseResponse doCompleteRepair(CompleteRepairRequest request) {
        DamageCasePo dc = requireCase(request.caseNo());
        if (!CaseStatus.REPAIR_APPROVED.name().equals(dc.status())) {
            throw ApiException.unprocessable("INVALID_STATE",
                    "案件状态 " + dc.status() + " 不允许申报修理完工");
        }
        RepairCyclePo cycle = cycleRepository.findByCaseAndAttempt(dc.id(), dc.repairAttempts());
        long completedAt = now(request.completedAtUtc());
        if (!TimeWindows.withinApprovalWindow(completedAt, cycle.validUntilUtc())) {
            // 逾期事实需独立事务落库：外层 422 回滚后，案件方可进入“逾期重新估价”分支。
            long cycleId = cycle.id();
            long caseId = dc.id();
            requiresNewTx.executeWithoutResult(s -> {
                cycleRepository.markCompleted(cycleId, request.requestId(), completedAt, "EXPIRED");
                eventRepository.insert(caseId, "REPAIR_EXPIRED",
                        "完工时刻已超过批复 7 个自然日有效期，须重新估价", clock.millis());
            });
            throw ApiException.unprocessable("APPROVAL_EXPIRED",
                    "修理批复已逾期（恰第 7 个自然日有效），须重新估价");
        }
        cycleRepository.markCompleted(cycle.id(), request.requestId(), completedAt, "COMPLETED");
        caseRepository.updateStatus(dc.id(), CaseStatus.REPAIR_COMPLETED.name(), clock.millis());
        eventRepository.insert(dc.id(), "REPAIR_COMPLETED",
                "第 " + dc.repairAttempts() + " 次修理完工待复检", clock.millis());
        return buildView(requireCase(request.caseNo()));
    }

    @Transactional
    public CaseResponse reinspect(ReinspectRequest request) {
        String fingerprint = fingerprint("REINSPECT", request);
        return idempotencyGate.run("REINSPECT", request.requestId(), fingerprint, 200,
                CaseResponse.class, () -> doReinspect(request));
    }

    private CaseResponse doReinspect(ReinspectRequest request) {
        DamageCasePo dc = requireCase(request.caseNo());
        if (!CaseStatus.REPAIR_COMPLETED.name().equals(dc.status())) {
            throw ApiException.unprocessable("INVALID_STATE",
                    "案件状态 " + dc.status() + " 不允许复检");
        }
        RepairCyclePo cycle = cycleRepository.findByCaseAndAttempt(dc.id(), dc.repairAttempts());
        long reinspectedAt = now(request.reinspectedAtUtc());
        long eventAt = clock.millis();
        if (Boolean.TRUE.equals(request.passed())) {
            cycleRepository.markReinspected(cycle.id(), request.requestId(), reinspectedAt,
                    "PASS", "PASSED");
            caseRepository.updateStatus(dc.id(), CaseStatus.REINSPECT_PASSED.name(), eventAt);
            eventRepository.insert(dc.id(), "REINSPECT_PASSED",
                    "第 " + dc.repairAttempts() + " 次复检合格，待放行", eventAt);
        } else {
            // 先统计既往 FAIL（不含本次），再标记本次结果：首次 FAIL 允许返修，第二次 FAIL 全损。
            int previousFails = cycleRepository.countFailByCaseId(dc.id());
            cycleRepository.markReinspected(cycle.id(), request.requestId(), reinspectedAt,
                    "FAIL", "FAILED");
            if (previousFails >= 1) {
                // 返修后仍不合格（本次为第二次复检不合格）：转全损评估并唯一结案。
                caseRepository.close(dc.id(), CaseStatus.TOTAL_LOSS.name(),
                        CaseStatus.TOTAL_LOSS.name(), eventAt);
                eventRepository.insert(dc.id(), "TOTAL_LOSS_DECLARED",
                        "唯一一次返修后复检仍不合格，转全损", eventAt);
            } else {
                caseRepository.updateStatus(dc.id(), CaseStatus.REWORK_REQUIRED.name(), eventAt);
                eventRepository.insert(dc.id(), "REWORK_REQUIRED",
                        "复检不合格，允许返修一次", eventAt);
            }
        }
        return buildView(requireCase(request.caseNo()));
    }

    @Transactional
    public CaseResponse release(ReleaseRequest request) {
        String fingerprint = fingerprint("RELEASE", request);
        return idempotencyGate.run("RELEASE", request.requestId(), fingerprint, 200,
                CaseResponse.class, () -> doRelease(request));
    }

    private CaseResponse doRelease(ReleaseRequest request) {
        DamageCasePo dc = requireCase(request.caseNo());
        if (CaseStatus.TOTAL_LOSS.name().equals(dc.status())) {
            throw ApiException.conflict("CASE_ALREADY_CLOSED",
                    "案件已按全损结案，不得放行");
        }
        if (CaseStatus.RELEASED.name().equals(dc.status())) {
            // 结案为终态：重复结案返回幂等成功。
            eventRepository.insert(dc.id(), "RELEASE_REPLAYED",
                    "重复结案请求，返回既有放行结果", clock.millis());
            return buildView(dc);
        }
        if (!CaseStatus.REINSPECT_PASSED.name().equals(dc.status())) {
            throw ApiException.unprocessable("INVALID_STATE",
                    "案件状态 " + dc.status() + " 不允许放行");
        }
        long releasedAt = now(request.releasedAtUtc());
        // 放箱与残损案关闭在同一事务内完成，不存在先放箱后补案。
        eventRepository.insert(dc.id(), "RELEASED", "复检合格放行，案件结案", releasedAt);
        caseRepository.close(dc.id(), CaseStatus.RELEASED.name(),
                CaseStatus.RELEASED.name(), releasedAt);
        return buildView(requireCase(request.caseNo()));
    }

    @Transactional(readOnly = true)
    public CaseResponse getCase(String caseNo) {
        return buildView(requireCase(caseNo));
    }

    private boolean latestCycleExpired(DamageCasePo dc) {
        RepairCyclePo cycle = cycleRepository.findByCaseAndAttempt(dc.id(), dc.repairAttempts());
        return cycle != null && "EXPIRED".equals(cycle.status());
    }

    private EstimateVersionPo currentEstimate(DamageCasePo dc) {
        return dc.currentEstimateId() == null ? null
                : estimateRepository.findById(dc.currentEstimateId());
    }

    private Map<String, IiclCodePo> resolveCodes(List<SurveyItemDto> items) {
        List<String> codes = items.stream().map(SurveyItemDto::code).distinct().toList();
        Map<String, IiclCodePo> resolved = iiclCodeRepository.findByCodes(codes).stream()
                .collect(Collectors.toMap(IiclCodePo::code, Function.identity()));
        List<String> missing = codes.stream().filter(c -> !resolved.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("UNKNOWN_IICL_CODE",
                    "未知残损代码: " + String.join(",", missing));
        }
        return resolved;
    }

    private DamageCasePo requireCase(String caseNo) {
        return caseRepository.findByCaseNo(caseNo)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseNo));
    }

    private boolean isClosed(DamageCasePo dc) {
        return CaseStatus.RELEASED.name().equals(dc.status())
                || CaseStatus.TOTAL_LOSS.name().equals(dc.status());
    }

    private long now(Long suppliedUtc) {
        return suppliedUtc != null ? suppliedUtc : clock.millis();
    }

    private String fingerprint(String operation, Object request) {
        try {
            String raw = operation + "|" + objectMapper.writeValueAsString(request);
            return Fingerprints.sha256(raw);
        } catch (Exception e) {
            throw new IllegalStateException("业务指纹计算失败", e);
        }
    }

    private CaseResponse buildView(DamageCasePo dc) {
        List<EstimateVersionPo> versionPos = estimateRepository.findAllByCaseId(dc.id());
        List<EstimateView> estimates = new ArrayList<>();
        EstimateView current = null;
        for (EstimateVersionPo v : versionPos) {
            EstimateView view = new EstimateView(v.id(), v.versionNo(), v.subtotalOwner(),
                    v.subtotalTerminal(), v.totalAmount(), v.maxItemAmount(), v.ratio(),
                    v.decision());
            estimates.add(view);
            if (v.id().equals(dc.currentEstimateId())) {
                current = view;
            }
        }
        List<DamageItemView> itemViews = itemRepository.findByCaseId(dc.id()).stream()
                .map(i -> new DamageItemView(i.code(), i.location(), i.responsibility(),
                        i.repairCode(), i.repairName(), i.unitCost(), i.quantity(), i.amount()))
                .toList();
        List<RepairCycleView> cycleViews = new ArrayList<>();
        for (RepairCyclePo c : cycleRepository.findByCaseId(dc.id())) {
            cycleViews.add(new RepairCycleView(c.attemptNo(), c.status(), c.approvedAtUtc(),
                    c.validUntilUtc(), c.completedAtUtc(), c.reinspectedAtUtc(),
                    c.reinspectResult()));
        }
        List<EventView> eventViews = new ArrayList<>();
        for (var e : eventRepository.findByCaseId(dc.id())) {
            eventViews.add(new EventView(e.eventType(), e.payload(), e.createdAtUtc()));
        }
        return new CaseResponse(dc.caseNo(), dc.containerNo(), dc.containerValue(), dc.status(),
                dc.closeOutcome(), dc.approvedAtUtc(), dc.validUntilUtc(), dc.repairAttempts(),
                dc.createdAtUtc(), dc.closedAtUtc(), current, estimates, itemViews, cycleViews,
                eventViews);
    }
}
