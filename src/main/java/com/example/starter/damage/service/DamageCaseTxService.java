package com.example.starter.damage.service;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.dto.Requests.ApprovalRequest;
import com.example.starter.damage.dto.Requests.CloseRequest;
import com.example.starter.damage.dto.Requests.CompletionRequest;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.PhotoRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.ReinspectionRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.Views.ApprovalView;
import com.example.starter.damage.dto.Views.CaseDetailView;
import com.example.starter.damage.dto.Views.CaseView;
import com.example.starter.damage.dto.Views.CompletionView;
import com.example.starter.damage.dto.Views.EstimateView;
import com.example.starter.damage.dto.Views.PhotoResult;
import com.example.starter.damage.store.DamageCaseRepository;
import com.example.starter.damage.store.DamageCaseRepository.CaseRow;
import com.example.starter.damage.store.DamageFlowRepository;
import com.example.starter.damage.store.InquiryRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * 残损案事务内业务服务：状态机流转、30% 全损阈值、批复 7 个 UTC 自然日有效期、
 * 返修一次限制与判定唯一性均在此强制。所有写方法在事务内执行。
 */
@Service
public class DamageCaseTxService {

    /** 全损阈值：单项修理费超过箱值 30% 判全损，恰 30% 按修理处理 */
    static final BigDecimal TOTAL_LOSS_THRESHOLD = new BigDecimal("0.30");

    /** 修理批复有效期：7 个 UTC 自然日，批复日为第 1 日，恰第 7 日完工有效 */
    static final long APPROVAL_VALID_DAYS = 7;

    private static final Set<CaseStatus> TERMINAL = EnumSet.of(CaseStatus.RELEASED, CaseStatus.TOTAL_LOSS);

    private final DamageCaseRepository caseRepository;
    private final DamageFlowRepository flowRepository;
    private final InquiryRepository inquiryRepository;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;

    public DamageCaseTxService(DamageCaseRepository caseRepository,
                               DamageFlowRepository flowRepository,
                               InquiryRepository inquiryRepository,
                               IdempotencyExecutor idempotency,
                               Clock clock) {
        this.caseRepository = caseRepository;
        this.flowRepository = flowRepository;
        this.inquiryRepository = inquiryRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /** 到港登记：同一箱号同时刻只允许一个进行中残损案，由 active_key 唯一索引仲裁 */
    @Transactional
    public CaseView register(RegisterRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        long caseId;
        try {
            caseId = caseRepository.insert(request.containerNo(), request.containerValue(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一箱号已存在进行中的残损案，仅唯一受理");
        }
        CaseView view = loadCase(caseId).toView();
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /** 检验：按合成 IICL 代码登记残损项并区分责任方，仅在 REGISTERED 状态可登记 */
    @Transactional
    public CaseDetailView survey(long caseId, SurveyRequest request) {
        CaseRow row = lockCase(caseId);
        requireStatus(row, CaseStatus.REGISTERED, "仅到港登记状态可登记检验残损项");
        for (var item : request.items()) {
            if (!flowRepository.iiclCodeExists(item.iiclCode())) {
                throw ApiException.badRequest("未知 IICL 残损代码: " + item.iiclCode());
            }
            flowRepository.insertItem(caseId, item.iiclCode(), item.component(),
                    item.description(), item.responsibility(), item.repairCost());
        }
        caseRepository.updateState(caseId, CaseStatus.SURVEYED, row.determination(), LocalDateTime.now(clock));
        CaseDetailView view = detail(caseId);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 估价：生成新估价版本，旧版本置 SUPERSEDED；单项修理费超箱值 30% 判全损。
     * 批复逾期后须重新估价；批复仍在有效期内时拒绝重复估价。
     */
    @Transactional
    public EstimateView estimate(long caseId, EstimateRequest request) {
        CaseRow row = lockCase(caseId);
        if (row.status() == CaseStatus.APPROVED) {
            ApprovalView active = flowRepository.findActiveApproval(caseId);
            LocalDate today = LocalDate.now(clock);
            if (active != null && !today.isAfter(active.approvalDate().plusDays(APPROVAL_VALID_DAYS - 1))) {
                throw ApiException.conflict("修理批复仍在有效期内，无需重新估价");
            }
        } else if (row.status() != CaseStatus.SURVEYED && row.status() != CaseStatus.ESTIMATED) {
            throw ApiException.conflict("当前状态不允许估价: " + row.status());
        }
        if (row.status() == CaseStatus.ESTIMATED) {
            // 询价/开标进行中固定残损项集合与全损判定，禁止重新估价；零有效报价（NO_AWARD）后允许
            if (inquiryRepository.hasOpenOrOpenedInquiry(caseId)) {
                throw ApiException.unprocessable("密封询价进行中，残损项集合与判定已固定，不能重新估价");
            }
        }
        var items = flowRepository.listItems(caseId);
        if (items.isEmpty()) {
            throw ApiException.badRequest("残损清单为空，无法估价");
        }
        BigDecimal threshold = row.containerValue().multiply(TOTAL_LOSS_THRESHOLD);
        BigDecimal total = items.stream()
                .map(item -> item.repairCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Determination determination = items.stream()
                .anyMatch(item -> item.repairCost().compareTo(threshold) > 0)
                ? Determination.TOTAL_LOSS
                : Determination.REPAIR;
        flowRepository.supersedeActiveApprovals(caseId);
        long estimateId = flowRepository.insertEstimate(caseId, total, determination,
                request.requestId(), LocalDateTime.now(clock));
        caseRepository.updateState(caseId, CaseStatus.ESTIMATED, determination, LocalDateTime.now(clock));
        EstimateView view = flowRepository.listEstimates(caseId).stream()
                .filter(e -> e.id() == estimateId)
                .findFirst()
                .orElseThrow();
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /** 修理批复：仅修理判定可批复，全损与修理不得并发成立 */
    @Transactional
    public ApprovalView approve(long caseId, ApprovalRequest request) {
        CaseRow row = lockCase(caseId);
        requireStatus(row, CaseStatus.ESTIMATED, "仅已估价状态可批复修理");
        if (row.determination() != Determination.REPAIR) {
            throw ApiException.conflict("全损案件不得批复修理，判定唯一");
        }
        // 询价投标/开标进行中必须经密封比价，旧批复入口不得绕过；零有效报价（NO_AWARD）后旧入口兼容
        if (inquiryRepository.hasOpenOrOpenedInquiry(caseId)) {
            throw ApiException.unprocessable("密封询价进行中，须经开标比价与双重复核批复，不能直接批复");
        }
        EstimateView current = flowRepository.findCurrentEstimate(caseId);
        if (current == null) {
            throw ApiException.conflict("无有效估价版本，无法批复");
        }
        LocalDate approvalDate = request.approvalDate() != null ? request.approvalDate() : LocalDate.now(clock);
        long approvalId = flowRepository.insertApproval(caseId, current.id(), approvalDate, LocalDateTime.now(clock));
        caseRepository.updateState(caseId, CaseStatus.APPROVED, row.determination(), LocalDateTime.now(clock));
        ApprovalView view = flowRepository.listApprovals(caseId).stream()
                .filter(a -> a.id() == approvalId)
                .findFirst()
                .orElseThrow();
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /** 修理（或返修）完工：完工日期须在批复 7 个 UTC 自然日有效期内，恰第 7 日有效 */
    @Transactional
    public CompletionView completeRepair(long caseId, CompletionRequest request) {
        CaseRow row = lockCase(caseId);
        if (row.status() != CaseStatus.APPROVED && row.status() != CaseStatus.REWORK) {
            throw ApiException.conflict("当前状态不允许登记完工: " + row.status());
        }
        ApprovalView active = flowRepository.findActiveApproval(caseId);
        if (active == null) {
            throw ApiException.conflict("无有效修理批复，无法登记完工");
        }
        LocalDate completionDate = request.completionDate() != null ? request.completionDate() : LocalDate.now(clock);
        LocalDate lastValidDay = active.approvalDate().plusDays(APPROVAL_VALID_DAYS - 1);
        if (completionDate.isAfter(lastValidDay)) {
            throw ApiException.conflict("修理批复已逾期（有效期 7 个自然日），须重新估价");
        }
        boolean rework = row.status() == CaseStatus.REWORK;
        flowRepository.insertCompletion(caseId, completionDate, rework, LocalDateTime.now(clock));
        caseRepository.updateState(caseId, CaseStatus.REPAIR_DONE, row.determination(),
                row.reworkCount(), LocalDateTime.now(clock));
        CompletionView view = flowRepository.listCompletions(caseId).stream()
                .reduce((first, second) -> second)
                .orElseThrow();
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /** 复检：不合格仅允许返修一次，返修后仍不合格转全损评估 */
    @Transactional
    public CaseView reinspect(long caseId, ReinspectionRequest request) {
        CaseRow row = lockCase(caseId);
        requireStatus(row, CaseStatus.REPAIR_DONE, "仅修理完工后可复检");
        flowRepository.insertReinspection(caseId, request.passed(), LocalDateTime.now(clock));
        LocalDateTime now = LocalDateTime.now(clock);
        if (request.passed()) {
            caseRepository.updateState(caseId, CaseStatus.REPAIR_PASSED, row.determination(),
                    row.reworkCount(), now);
        } else if (row.reworkCount() == 0) {
            caseRepository.updateState(caseId, CaseStatus.REWORK, row.determination(), 1, now);
        } else {
            flowRepository.supersedeActiveApprovals(caseId);
            caseRepository.updateState(caseId, CaseStatus.TOTAL_LOSS_PENDING, Determination.TOTAL_LOSS,
                    row.reworkCount(), now);
        }
        CaseView view = loadCase(caseId).toView();
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 结案：修理放行与案件关闭在同一事务内完成；结案为终态，重复结案返回幂等成功。
     */
    @Transactional
    public CaseView close(long caseId, CloseRequest request) {
        CaseRow row = lockCase(caseId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (TERMINAL.contains(row.status())) {
            CaseView view = row.toView();
            idempotency.completeInTransaction(request.requestId(), view);
            return view;
        }
        if (row.status() == CaseStatus.REPAIR_PASSED) {
            caseRepository.close(caseId, CaseStatus.RELEASED, now);
        } else if (row.determination() == Determination.TOTAL_LOSS
                && (row.status() == CaseStatus.ESTIMATED || row.status() == CaseStatus.TOTAL_LOSS_PENDING)) {
            caseRepository.close(caseId, CaseStatus.TOTAL_LOSS, now);
        } else {
            throw ApiException.conflict("当前状态不允许结案: " + row.status());
        }
        CaseView view = loadCase(caseId).toView();
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /** 检验照片：结案后迟到照片只记迟到事件，不改变案件状态 */
    @Transactional
    public PhotoResult photo(long caseId, PhotoRequest request) {
        CaseRow row = lockCase(caseId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (TERMINAL.contains(row.status())) {
            flowRepository.insertEvent(caseId, "LATE_PHOTO", request.photoRef(), now);
            return new PhotoResult("LATE_RECORDED");
        }
        flowRepository.insertEvent(caseId, "PHOTO", request.photoRef(), now);
        return new PhotoResult("ATTACHED");
    }

    /** 案件全量明细：残损清单、估价版本与修理链均可复算 */
    @Transactional(readOnly = true)
    public CaseDetailView detail(long caseId) {
        CaseRow row = caseRepository.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
        return new CaseDetailView(
                row.toView(),
                flowRepository.listItems(caseId),
                flowRepository.listEstimates(caseId),
                flowRepository.listApprovals(caseId),
                flowRepository.listCompletions(caseId),
                flowRepository.listReinspections(caseId),
                flowRepository.listEvents(caseId));
    }

    private CaseRow lockCase(long caseId) {
        return caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
    }

    private CaseRow loadCase(long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
    }

    private void requireStatus(CaseRow row, CaseStatus expected, String message) {
        if (row.status() != expected) {
            throw ApiException.conflict(message + "，当前状态: " + row.status());
        }
    }
}
