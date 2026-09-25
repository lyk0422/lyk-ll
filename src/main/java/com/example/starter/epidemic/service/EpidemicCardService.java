package com.example.starter.epidemic.service;

import com.example.starter.epidemic.api.ActionResult;
import com.example.starter.epidemic.api.CardView;
import com.example.starter.epidemic.api.CorrectionRequest;
import com.example.starter.epidemic.api.InitialReportRequest;
import com.example.starter.epidemic.api.SpecialConfirmRequest;
import com.example.starter.epidemic.api.SpecialCorrectionRequest;
import com.example.starter.epidemic.api.SpecialCorrectionView;
import com.example.starter.epidemic.api.SubmitResult;
import com.example.starter.epidemic.domain.DiagnosisType;
import com.example.starter.epidemic.repo.CardPo;
import com.example.starter.epidemic.repo.CardRepository;
import com.example.starter.epidemic.repo.CardVersionPo;
import com.example.starter.epidemic.repo.CardVersionRepository;
import com.example.starter.epidemic.repo.DiseasePo;
import com.example.starter.epidemic.repo.CatalogRepository;
import com.example.starter.epidemic.repo.IdempotentRequestPo;
import com.example.starter.epidemic.repo.IdempotentRequestRepository;
import com.example.starter.epidemic.repo.ReviewPo;
import com.example.starter.epidemic.repo.ReviewRepository;
import com.example.starter.epidemic.repo.SpecialCorrectionPo;
import com.example.starter.epidemic.repo.SpecialCorrectionRepository;
import com.example.starter.epidemic.support.TimeProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 传染病报告卡订正与迟报判定核心服务。
 * 流转：初报→审核→订正（病种变更重起审核）→终审归档；归档后仅双人特殊订正可追加版本。
 */
@Service
public class EpidemicCardService {

    private final CardRepository cardRepository;
    private final CardVersionRepository versionRepository;
    private final ReviewRepository reviewRepository;
    private final IdempotentRequestRepository idempotentRepository;
    private final SpecialCorrectionRepository specialRepository;
    private final CatalogRepository catalogRepository;
    private final CardTxOperations txOperations;
    private final CardViewAssembler assembler;
    private final FingerprintCalculator fingerprintCalculator;
    private final TimeProvider timeProvider;
    private final TransactionTemplate txTemplate;

    public EpidemicCardService(CardRepository cardRepository,
                               CardVersionRepository versionRepository,
                               ReviewRepository reviewRepository,
                               IdempotentRequestRepository idempotentRepository,
                               SpecialCorrectionRepository specialRepository,
                               CatalogRepository catalogRepository,
                               CardTxOperations txOperations,
                               CardViewAssembler assembler,
                               FingerprintCalculator fingerprintCalculator,
                               TimeProvider timeProvider,
                               PlatformTransactionManager transactionManager) {
        this.cardRepository = cardRepository;
        this.versionRepository = versionRepository;
        this.reviewRepository = reviewRepository;
        this.idempotentRepository = idempotentRepository;
        this.specialRepository = specialRepository;
        this.catalogRepository = catalogRepository;
        this.txOperations = txOperations;
        this.assembler = assembler;
        this.fingerprintCalculator = fingerprintCalculator;
        this.timeProvider = timeProvider;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // ============================ 初报 ============================

    public SubmitResult submitInitial(InitialReportRequest request) {
        DiseasePo disease = requireEnabledDisease(request.diseaseCode());
        requireEnum(request.diagnosisType(), DiagnosisType.class, "diagnosisType");
        if (!catalogRepository.facilityEnabled(request.facilityCode())) {
            throw new BusinessException("FACILITY_NOT_FOUND",
                    "报告机构不存在或已停用：" + request.facilityCode(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String fingerprint = fingerprintCalculator.sha256(
                "INITIAL", request.requestId(), request.patientId(), nvl(request.patientName()),
                request.diseaseCode(), request.diagnosisType(), request.onsetDate(),
                ReportingRules.toUtc(request.diagnosisTime()), request.facilityCode(),
                request.reporter(), nvl(trim(request.lateReason())), nvl(request.notes()));

        var existing = idempotentRepository.find(request.requestId());
        if (existing.isPresent()) {
            return replaySubmit(existing.get(), fingerprint, "INITIAL");
        }

        LocalDateTime submittedAt = timeProvider.nowUtc();
        LocalDateTime diagnosisUtc = ReportingRules.toUtc(request.diagnosisTime());
        LocalDateTime deadlineUtc = ReportingRules.deadline(disease, diagnosisUtc);
        boolean timely = ReportingRules.isTimely(deadlineUtc, submittedAt);
        validateLateReason(timely, request.lateReason());

        try {
            CardTxOperations.SubmitOutcome outcome = txTemplate.execute(status ->
                    txOperations.createInitial(request, disease, fingerprint,
                            diagnosisUtc, submittedAt, deadlineUtc, timely));
            return new SubmitResult(outcome.cardNumber(), outcome.versionNo(), outcome.status(),
                    outcome.timely(), outcome.duplicated(), false, outcome.existingCardNumber());
        } catch (DuplicateKeyException dup) {
            // 提交时唯一冲突（同 requestId 或去重三元组）：事务已整体回滚，在事务外重查胜者重放。
            return resolveInitialConflict(request, fingerprint, timely);
        }
    }

    /** 唯一冲突后判定是幂等键竞争还是重复卡去重，返回首个结果且不新建卡。 */
    private SubmitResult resolveInitialConflict(InitialReportRequest request, String fingerprint, boolean timely) {
        var winner = idempotentRepository.find(request.requestId());
        if (winner.isPresent()) {
            return replaySubmit(winner.get(), fingerprint, "INITIAL");
        }
        CardPo winnerCard = cardRepository
                .findActiveDedup(request.patientId(), request.diseaseCode(), request.onsetDate())
                .orElseThrow(() -> new BusinessException("CARD_NOT_FOUND",
                        "并发去重状态异常", HttpStatus.CONFLICT));
        txTemplate.executeWithoutResult(status ->
                txOperations.recordDuplicateInitial(request.requestId(), fingerprint,
                        winnerCard.cardNumber, winnerCard.currentVersion, timeProvider.nowUtc()));
        return new SubmitResult(winnerCard.cardNumber, winnerCard.currentVersion,
                winnerCard.status, timely, true, false, winnerCard.cardNumber);
    }

    // ============================ 订正 ============================

    public SubmitResult submitCorrection(CorrectionRequest request) {
        CardPo card = requireCard(request.cardNumber());
        DiseasePo disease = requireEnabledDisease(request.diseaseCode());
        requireEnum(request.diagnosisType(), DiagnosisType.class, "diagnosisType");
        switch (CardStatusSafe.of(card.status)) {
            case PENDING_REVIEW -> throw new BusinessException("CARD_NOT_APPROVED",
                    "卡片尚未审核通过，不能订正", HttpStatus.CONFLICT);
            case WITHDRAWN -> throw new BusinessException("CARD_WITHDRAWN",
                    "卡片已撤回（终态），不能订正", HttpStatus.CONFLICT);
            case ARCHIVED -> throw new BusinessException("CARD_ARCHIVED",
                    "卡片已归档终审，普通订正一律拒绝；疫情核实确需变更请走特殊订正",
                    HttpStatus.CONFLICT);
            case APPROVED -> {
                // 唯一允许普通订正的状态。
            }
        }

        String fingerprint = fingerprintCalculator.sha256(
                "CORRECTION", request.requestId(), request.cardNumber(),
                request.diseaseCode(), request.diagnosisType(), request.onsetDate(),
                ReportingRules.toUtc(request.diagnosisTime()), request.reporter(),
                nvl(trim(request.lateReason())), nvl(request.notes()));

        var existing = idempotentRepository.find(request.requestId());
        if (existing.isPresent()) {
            return replaySubmit(existing.get(), fingerprint, "CORRECTION");
        }

        LocalDateTime submittedAt = timeProvider.nowUtc();
        LocalDateTime diagnosisUtc = ReportingRules.toUtc(request.diagnosisTime());
        LocalDateTime deadlineUtc = ReportingRules.deadline(disease, diagnosisUtc);
        boolean timely = ReportingRules.isTimely(deadlineUtc, submittedAt);
        validateLateReason(timely, request.lateReason());

        try {
            CardTxOperations.SubmitOutcome outcome = txTemplate.execute(status ->
                    txOperations.createCorrection(request, card, disease, fingerprint,
                            diagnosisUtc, submittedAt, deadlineUtc, timely));
            return new SubmitResult(outcome.cardNumber(), outcome.versionNo(), outcome.status(),
                    outcome.timely(), false, false, null);
        } catch (DuplicateKeyException dup) {
            var winner = idempotentRepository.find(request.requestId());
            if (winner.isPresent()) {
                return replaySubmit(winner.get(), fingerprint, "CORRECTION");
            }
            throw new BusinessException("DEDUP_CONFLICT",
                    "订正后的患者+病种+发病日期已被其他有效卡占用", HttpStatus.CONFLICT);
        }
    }

    // ============================ 初审 / 撤回 / 归档 ============================

    @Transactional
    public ActionResult review(String cardNumber, String reviewer, String opinion) {
        CardPo card = requireCard(cardNumber);
        if (CardStatusSafe.of(card.status) == CardStatusSafe.APPROVED) {
            return new ActionResult(card.cardNumber, card.status, card.currentVersion,
                    true, "卡片已审核通过，重复审核幂等返回");
        }
        if (CardStatusSafe.of(card.status) == CardStatusSafe.ARCHIVED) {
            throw new BusinessException("CARD_ARCHIVED", "卡片已归档终审", HttpStatus.CONFLICT);
        }
        if (CardStatusSafe.of(card.status) == CardStatusSafe.WITHDRAWN) {
            throw new BusinessException("CARD_WITHDRAWN",
                    "卡片已撤回（终态），审核未生效", HttpStatus.CONFLICT);
        }
        boolean approved = txOperations.approveReview(card, reviewer, opinion);
        if (!approved) {
            CardPo fresh = requireCard(cardNumber);
            if (CardStatusSafe.of(fresh.status) == CardStatusSafe.WITHDRAWN) {
                throw new BusinessException("REVIEW_LOST_RACE",
                        "审核与撤回并发，撤回已形成唯一终态，审核未生效", HttpStatus.CONFLICT);
            }
            return new ActionResult(fresh.cardNumber, fresh.status, fresh.currentVersion,
                    true, "审核已完成，幂等返回");
        }
        return new ActionResult(cardNumber, "APPROVED", card.currentVersion, false, "审核通过");
    }

    @Transactional
    public ActionResult withdraw(String cardNumber, String operator, String reason) {
        CardPo card = requireCard(cardNumber);
        if (CardStatusSafe.of(card.status) == CardStatusSafe.WITHDRAWN) {
            return new ActionResult(card.cardNumber, card.status, card.currentVersion,
                    true, "卡片已撤回，重复撤回幂等返回；撤回为终态不可恢复");
        }
        if (CardStatusSafe.of(card.status) == CardStatusSafe.APPROVED) {
            throw new BusinessException("ALREADY_APPROVED",
                    "卡片已审核通过，不能撤回", HttpStatus.CONFLICT);
        }
        if (CardStatusSafe.of(card.status) == CardStatusSafe.ARCHIVED) {
            throw new BusinessException("CARD_ARCHIVED", "卡片已归档终审，不能撤回", HttpStatus.CONFLICT);
        }
        boolean withdrawn = txOperations.withdraw(card, operator, reason);
        if (!withdrawn) {
            CardPo fresh = requireCard(cardNumber);
            if (CardStatusSafe.of(fresh.status) == CardStatusSafe.APPROVED) {
                throw new BusinessException("WITHDRAW_LOST_RACE",
                        "撤回与审核并发，审核通过已形成终态，撤回未生效", HttpStatus.CONFLICT);
            }
            if (CardStatusSafe.of(fresh.status) == CardStatusSafe.WITHDRAWN) {
                return new ActionResult(fresh.cardNumber, fresh.status, fresh.currentVersion,
                        true, "卡片已撤回，幂等返回");
            }
            throw new BusinessException("WITHDRAW_REJECTED", "当前状态不能撤回", HttpStatus.CONFLICT);
        }
        return new ActionResult(cardNumber, "WITHDRAWN", card.currentVersion, false,
                "撤回成功，终态不可恢复");
    }

    @Transactional
    public ActionResult archive(String cardNumber, String reviewer, String opinion) {
        CardPo card = requireCard(cardNumber);
        if (CardStatusSafe.of(card.status) == CardStatusSafe.ARCHIVED) {
            return new ActionResult(card.cardNumber, card.status, card.currentVersion,
                    true, "卡片已归档，重复归档幂等成功；归档后只读");
        }
        if (CardStatusSafe.of(card.status) == CardStatusSafe.WITHDRAWN) {
            throw new BusinessException("CARD_WITHDRAWN", "卡片已撤回，不能归档", HttpStatus.CONFLICT);
        }
        if (CardStatusSafe.of(card.status) == CardStatusSafe.PENDING_REVIEW) {
            throw new BusinessException("CARD_NOT_APPROVED",
                    "卡片尚未审核通过，不能终审归档", HttpStatus.CONFLICT);
        }
        boolean archived = txOperations.archive(card, reviewer, opinion);
        if (!archived) {
            CardPo fresh = requireCard(cardNumber);
            if (CardStatusSafe.of(fresh.status) == CardStatusSafe.ARCHIVED) {
                return new ActionResult(fresh.cardNumber, fresh.status, fresh.currentVersion,
                        true, "卡片已归档，重复归档幂等成功");
            }
            throw new BusinessException("ARCHIVE_REJECTED", "当前状态不能归档", HttpStatus.CONFLICT);
        }
        return new ActionResult(cardNumber, "ARCHIVED", card.currentVersion, false, "终审归档成功，卡片只读");
    }

    // ============================ 特殊订正（双人确认） ============================

    public SpecialCorrectionView submitSpecial(SpecialCorrectionRequest request) {
        CardPo card = requireCard(request.cardNumber());
        if (CardStatusSafe.of(card.status) != CardStatusSafe.ARCHIVED) {
            throw new BusinessException("SPECIAL_REQUIRES_ARCHIVED",
                    "特殊订正仅受理已归档卡片", HttpStatus.CONFLICT);
        }
        DiseasePo disease = requireEnabledDisease(request.diseaseCode());
        requireEnum(request.diagnosisType(), DiagnosisType.class, "diagnosisType");
        if (isBlank(request.notes())) {
            throw new BusinessException("SPECIAL_NOTES_REQUIRED",
                    "特殊订正须说明疫情核实情况", HttpStatus.BAD_REQUEST);
        }

        String fingerprint = fingerprintCalculator.sha256(
                "SPECIAL", request.requestId(), request.cardNumber(),
                request.diseaseCode(), request.diagnosisType(), request.onsetDate(),
                ReportingRules.toUtc(request.diagnosisTime()),
                nvl(trim(request.lateReason())), nvl(request.notes()));

        var existing = specialRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replaySpecial(existing.get(), fingerprint);
        }

        LocalDateTime diagnosisUtc = ReportingRules.toUtc(request.diagnosisTime());
        LocalDateTime deadlineUtc = ReportingRules.deadline(disease, diagnosisUtc);
        boolean timely = ReportingRules.isTimely(deadlineUtc, timeProvider.nowUtc());
        validateLateReason(timely, request.lateReason());

        try {
            txTemplate.executeWithoutResult(status ->
                    txOperations.createSpecial(request, fingerprint, diagnosisUtc, timely));
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 提交/插入时 requestId 冲突：事务回滚后按首次结果重放。
            SpecialCorrectionPo winner = requireSpecial(request.requestId());
            return replaySpecial(winner, fingerprint);
        }
        SpecialCorrectionPo created = requireSpecial(request.requestId());
        return assembler.toSpecialView(created, "特殊订正已发起，等待两名不同审核员先后确认");
    }

    public SpecialCorrectionView confirmSpecial(String requestId, SpecialConfirmRequest request) {
        requireSpecial(requestId);
        SpecialCorrectionPo fresh = txTemplate.execute(status -> {
            Integer appliedVersion = txOperations.confirmSpecial(
                    requestId, request.reviewer(), request.opinion());
            SpecialCorrectionPo po = specialRepository.findByRequestId(requestId).orElseThrow();
            if (appliedVersion != null) {
                po.appliedVersionNo = appliedVersion;
            }
            return po;
        });
        if ("APPLIED".equals(fresh.status) || fresh.appliedVersionNo != null) {
            return assembler.toSpecialView(fresh,
                    "两名不同审核员已确认，特殊订正生效，新版本号 " + fresh.appliedVersionNo);
        }
        if (request.reviewer().equals(fresh.confirmer1) || request.reviewer().equals(fresh.confirmer2)) {
            return assembler.toSpecialView(fresh, "同一审核员重复确认不增加有效确认人数，仍须另一名不同审核员确认");
        }
        if (fresh.confirmer2 != null) {
            return assembler.toSpecialView(fresh, "确认人数已满足，特殊订正处理中");
        }
        return assembler.toSpecialView(fresh, "已记录第一名审核员确认，还须另一名不同审核员确认后方生效");
    }

    // ============================ 查询 ============================

    @Transactional(readOnly = true)
    public CardView getCard(String cardNumber) {
        CardPo card = requireCard(cardNumber);
        return loadView(card);
    }

    @Transactional(readOnly = true)
    public SpecialCorrectionView getSpecial(String requestId) {
        SpecialCorrectionPo po = requireSpecial(requestId);
        String message = "APPLIED".equals(po.status)
                ? "特殊订正已生效，新版本号 " + po.appliedVersionNo
                : "待两名不同审核员确认";
        return assembler.toSpecialView(po, message);
    }

    @Transactional(readOnly = true)
    public List<CardView> listCards() {
        return cardRepository.findAll().stream().map(this::loadView).toList();
    }

    // ============================ 内部辅助 ============================

    private CardView loadView(CardPo card) {
        List<CardVersionPo> versions = versionRepository.findAllByCard(card.cardNumber);
        List<ReviewPo> reviews = reviewRepository.findAllByCard(card.cardNumber);
        return assembler.toCardView(card, versions, reviews);
    }

    private SubmitResult replaySubmit(IdempotentRequestPo first, String fingerprint, String expectedType) {
        if (!expectedType.equals(first.requestType)) {
            throw new BusinessException("IDEMPOTENT_REQUEST_TYPE_MISMATCH",
                    "requestId 已用于其他类型请求", HttpStatus.CONFLICT);
        }
        if (!fingerprint.equals(first.fingerprint)) {
            throw new BusinessException("IDEMPOTENT_PARAM_MISMATCH",
                    "同 requestId 参数与首次请求不一致，拒绝且不产生新版本（409）", HttpStatus.CONFLICT);
        }
        boolean duplicated = "DUPLICATE".equals(first.resultKind);
        return new SubmitResult(first.cardNumber, first.versionNo,
                statusOf(first.cardNumber), timelinessOf(first.cardNumber, first.versionNo),
                duplicated, true, duplicated ? first.cardNumber : null);
    }

    private SpecialCorrectionView replaySpecial(SpecialCorrectionPo first, String fingerprint) {
        if (!fingerprint.equals(first.fingerprint)) {
            throw new BusinessException("IDEMPOTENT_PARAM_MISMATCH",
                    "同 requestId 参数与首次特殊订正不一致，拒绝（409）", HttpStatus.CONFLICT);
        }
        return assembler.toSpecialView(first, "同 requestId 重放，返回首次结果");
    }

    private String statusOf(String cardNumber) {
        return requireCard(cardNumber).status;
    }

    private boolean timelinessOf(String cardNumber, int versionNo) {
        CardVersionPo v = versionRepository.find(cardNumber, versionNo);
        return v != null && v.timely;
    }

    private CardPo requireCard(String cardNumber) {
        return cardRepository.findByNumber(cardNumber)
                .orElseThrow(() -> new BusinessException("CARD_NOT_FOUND",
                        "卡片不存在：" + cardNumber, HttpStatus.NOT_FOUND));
    }

    private IdempotentRequestPo requireIdempotent(String requestId) {
        return idempotentRepository.find(requestId)
                .orElseThrow(() -> new BusinessException("IDEMPOTENT_STATE_UNKNOWN",
                        "幂等记录状态未知", HttpStatus.CONFLICT));
    }

    private SpecialCorrectionPo requireSpecial(String requestId) {
        return specialRepository.findByRequestId(requestId)
                .orElseThrow(() -> new BusinessException("SPECIAL_NOT_FOUND",
                        "特殊订正不存在：" + requestId, HttpStatus.NOT_FOUND));
    }

    private DiseasePo requireEnabledDisease(String code) {
        DiseasePo disease = catalogRepository.findDisease(code)
                .orElseThrow(() -> new BusinessException("DISEASE_NOT_FOUND",
                        "病种不存在：" + code, HttpStatus.UNPROCESSABLE_ENTITY));
        if (!disease.enabled) {
            throw new BusinessException("DISEASE_DISABLED",
                    "病种已停用：" + code, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return disease;
    }

    private static void validateLateReason(boolean timely, String lateReason) {
        if (!timely && isBlank(lateReason)) {
            throw new BusinessException("LATE_REASON_REQUIRED",
                    "已判定迟报，强制填写迟报原因", HttpStatus.BAD_REQUEST);
        }
        if (timely && !isBlank(lateReason)) {
            throw new BusinessException("LATE_REASON_NOT_ALLOWED",
                    "报告及时，不应填写迟报原因", HttpStatus.BAD_REQUEST);
        }
    }

    private static <E extends Enum<E>> E requireEnum(String value, Class<E> type, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("ENUM_INVALID",
                    field + " 取值非法：" + value, HttpStatus.BAD_REQUEST);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** 与 CardStatus 枚举字符串保持一致的轻量映射，避免领域包循环依赖噪音。 */
    private enum CardStatusSafe {
        PENDING_REVIEW, APPROVED, WITHDRAWN, ARCHIVED, UNKNOWN;

        static CardStatusSafe of(String status) {
            try {
                return valueOf(status);
            } catch (IllegalArgumentException ex) {
                return UNKNOWN;
            }
        }
    }
}
