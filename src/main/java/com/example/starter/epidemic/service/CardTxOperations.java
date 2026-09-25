package com.example.starter.epidemic.service;

import com.example.starter.epidemic.api.CorrectionRequest;
import com.example.starter.epidemic.api.InitialReportRequest;
import com.example.starter.epidemic.api.SpecialCorrectionRequest;
import com.example.starter.epidemic.domain.ChangeKind;
import com.example.starter.epidemic.domain.ReviewOutcome;
import com.example.starter.epidemic.domain.ReviewStage;
import com.example.starter.epidemic.repo.CardPo;
import com.example.starter.epidemic.repo.CardRepository;
import com.example.starter.epidemic.repo.CardVersionPo;
import com.example.starter.epidemic.repo.CardVersionRepository;
import com.example.starter.epidemic.repo.CatalogRepository;
import com.example.starter.epidemic.repo.DiseasePo;
import com.example.starter.epidemic.repo.IdempotentRequestPo;
import com.example.starter.epidemic.repo.IdempotentRequestRepository;
import com.example.starter.epidemic.repo.ReviewPo;
import com.example.starter.epidemic.repo.ReviewRepository;
import com.example.starter.epidemic.repo.SpecialCorrectionPo;
import com.example.starter.epidemic.repo.SpecialCorrectionRepository;
import com.example.starter.epidemic.support.TimeProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 报告卡写操作的事务内组件。本组件不自带事务边界，所有方法都在调用方
 * （{@link EpidemicCardService}）的事务内执行，从而可以安全地在同事务中
 * 捕获唯一约束冲突并改写结果，避免内层事务被标记 rollback-only。
 */
@Component
public class CardTxOperations {

    private final CardRepository cardRepository;
    private final CardVersionRepository versionRepository;
    private final ReviewRepository reviewRepository;
    private final IdempotentRequestRepository idempotentRepository;
    private final SpecialCorrectionRepository specialRepository;
    private final CatalogRepository catalogRepository;
    private final TimeProvider timeProvider;

    public CardTxOperations(CardRepository cardRepository,
                            CardVersionRepository versionRepository,
                            ReviewRepository reviewRepository,
                            IdempotentRequestRepository idempotentRepository,
                            SpecialCorrectionRepository specialRepository,
                            CatalogRepository catalogRepository,
                            TimeProvider timeProvider) {
        this.cardRepository = cardRepository;
        this.versionRepository = versionRepository;
        this.reviewRepository = reviewRepository;
        this.idempotentRepository = idempotentRepository;
        this.specialRepository = specialRepository;
        this.catalogRepository = catalogRepository;
        this.timeProvider = timeProvider;
    }

    /** 初报/订正/重复去重的事务结果。 */
    public record SubmitOutcome(String cardNumber, int versionNo, String status, boolean timely,
                                boolean duplicated, String existingCardNumber) {
    }

    /**
     * 初报建卡（调用方事务内执行）。
     *
     * @return CREATED 或 DUPLICATE 两种结果
     */
    public SubmitOutcome createInitial(InitialReportRequest req, DiseasePo disease,
                                       String fingerprint, LocalDateTime diagnosisUtc,
                                       LocalDateTime submittedAt, LocalDateTime deadlineUtc, boolean timely) {
        LocalDateTime now = timeProvider.nowUtc();
        var dedup = cardRepository.findActiveDedup(req.patientId(), req.diseaseCode(), req.onsetDate());
        if (dedup.isPresent()) {
            recordIdempotent(req.requestId(), "INITIAL", fingerprint, "DUPLICATE",
                    dedup.get().cardNumber, dedup.get().currentVersion, now);
            return new SubmitOutcome(dedup.get().cardNumber, dedup.get().currentVersion,
                    dedup.get().status, timely, true, dedup.get().cardNumber);
        }

        long seq = cardRepository.nextCardSequence();
        String cardNumber = "C" + String.format("%08d", seq);
        // 幂等记录先于建卡写入：并发同 requestId 落败方在此失败，不留下孤儿卡。
        recordIdempotent(req.requestId(), "INITIAL", fingerprint, "CREATED", cardNumber, 1, now);

        CardPo card = new CardPo();
        card.cardNumber = cardNumber;
        card.patientId = req.patientId();
        card.patientName = req.patientName();
        card.diseaseCode = req.diseaseCode();
        card.onsetDate = req.onsetDate();
        card.dedupKey = DedupKeys.of(req.patientId(), req.diseaseCode(), req.onsetDate());
        card.currentVersion = 1;
        card.status = "PENDING_REVIEW";
        card.createdAt = now;
        card.updatedAt = now;
        // 去重唯一索引冲突（含提交时检出）直接上抛，由编排层回滚整事务后重试重放。
        cardRepository.insert(card);

        CardVersionPo v = new CardVersionPo();
        v.cardNumber = cardNumber;
        v.versionNo = 1;
        v.diseaseCode = req.diseaseCode();
        v.diagnosisType = req.diagnosisType();
        v.onsetDate = req.onsetDate();
        v.diagnosisTime = diagnosisUtc;
        v.submittedAt = submittedAt;
        v.deadline = deadlineUtc;
        v.timely = timely;
        v.lateReason = trimToNull(req.lateReason());
        v.facilityCode = req.facilityCode();
        v.reporter = req.reporter();
        v.changeKind = ChangeKind.INITIAL.name();
        v.diseaseChanged = false;
        v.notes = req.notes();
        v.createdAt = now;
        versionRepository.insert(v);

        return new SubmitOutcome(cardNumber, 1, "PENDING_REVIEW", timely, false, null);
    }

    /**
     * 普通订正（调用方事务内执行）：仅 APPROVED 卡可进入；病种/诊断类型变更重起审核并作废原审核结论。
     */
    public SubmitOutcome createCorrection(CorrectionRequest req, CardPo input, DiseasePo disease,
                                          String fingerprint, LocalDateTime diagnosisUtc,
                                          LocalDateTime submittedAt, LocalDateTime deadlineUtc, boolean timely) {
        LocalDateTime now = timeProvider.nowUtc();
        // 锁卡行：串行化同卡的订正、审核与撤回。
        CardPo card = cardRepository.findForUpdate(input.cardNumber);
        if (card == null) {
            throw new BusinessException("CARD_NOT_FOUND",
                    "卡片不存在：" + input.cardNumber, org.springframework.http.HttpStatus.NOT_FOUND);
        }
        if (!"APPROVED".equals(card.status)) {
            throw new BusinessException("CARD_STATE_CHANGED",
                    "卡片状态已变更，订正被拒绝", org.springframework.http.HttpStatus.CONFLICT);
        }
        cardRepository.findActiveDedup(card.patientId, req.diseaseCode(), req.onsetDate())
                .filter(owner -> !owner.cardNumber.equals(card.cardNumber))
                .ifPresent(owner -> {
                    throw new BusinessException("DEDUP_CONFLICT",
                            "订正后的患者+病种+发病日期已存在有效卡：" + owner.cardNumber,
                            org.springframework.http.HttpStatus.CONFLICT);
                });
        CardVersionPo latest = versionRepository.findLatest(card.cardNumber);
        int newVersionNo = latest.versionNo + 1;
        boolean restartReview = !req.diseaseCode().equals(latest.diseaseCode)
                || !req.diagnosisType().equals(latest.diagnosisType);

        CardVersionPo v = new CardVersionPo();
        v.cardNumber = card.cardNumber;
        v.versionNo = newVersionNo;
        v.diseaseCode = req.diseaseCode();
        v.diagnosisType = req.diagnosisType();
        v.onsetDate = req.onsetDate();
        v.diagnosisTime = diagnosisUtc;
        v.submittedAt = submittedAt;
        v.deadline = deadlineUtc;
        v.timely = timely;
        v.lateReason = trimToNull(req.lateReason());
        v.facilityCode = latest.facilityCode;
        v.reporter = req.reporter();
        v.changeKind = ChangeKind.CORRECTION.name();
        v.diseaseChanged = restartReview;
        v.notes = req.notes();
        v.createdAt = now;

        String newStatus = restartReview ? "PENDING_REVIEW" : "APPROVED";
        int rows;
        try {
            rows = cardRepository.updateStateIfCurrentStatus(card.cardNumber, newStatus,
                    req.diseaseCode(), req.onsetDate(), card.patientId, newVersionNo, now,
                    card.currentVersion, "APPROVED");
        } catch (DuplicateKeyException dup) {
            // 目标去重三元组已被其他有效卡占用。
            throw new BusinessException("DEDUP_CONFLICT",
                    "订正后的患者+病种+发病日期已被其他有效卡占用",
                    org.springframework.http.HttpStatus.CONFLICT);
        }
        if (rows == 0) {
            throw new BusinessException("CARD_STATE_CHANGED",
                    "卡片状态或版本已变更，订正被拒绝", org.springframework.http.HttpStatus.CONFLICT);
        }
        versionRepository.insert(v);
        if (restartReview) {
            reviewRepository.voidActiveReviewApprovals(card.cardNumber);
        }
        // 幂等记录最后写：并发同 requestId 落败方在此冲突，整事务回滚并由编排层重放。
        recordIdempotent(req.requestId(), "CORRECTION", fingerprint, "CREATED",
                card.cardNumber, newVersionNo, now);
        return new SubmitOutcome(card.cardNumber, newVersionNo, newStatus, timely, false, null);
    }

    /**
     * 初审通过；PENDING_REVIEW→APPROVED 的条件更新裁决审核/撤回竞态。
     */
    public boolean approveReview(CardPo card, String reviewer, String opinion) {
        LocalDateTime now = timeProvider.nowUtc();
        int rows = cardRepository.updateStatusOnly(card.cardNumber, "APPROVED", now, "PENDING_REVIEW");
        if (rows == 0) {
            return false;
        }
        ReviewPo r = new ReviewPo();
        r.cardNumber = card.cardNumber;
        r.versionNo = card.currentVersion;
        r.stage = ReviewStage.REVIEW.name();
        r.reviewer = reviewer;
        r.opinion = opinion;
        r.outcome = ReviewOutcome.APPROVED.name();
        r.voided = false;
        r.createdAt = now;
        reviewRepository.insert(r);
        return true;
    }

    /**
     * 撤回；条件更新失败表示审核通过抢先形成终态。
     */
    public boolean withdraw(CardPo card, String operator, String reason) {
        LocalDateTime now = timeProvider.nowUtc();
        int rows = cardRepository.withdraw(card.cardNumber, now);
        if (rows == 0) {
            return false;
        }
        ReviewPo r = new ReviewPo();
        r.cardNumber = card.cardNumber;
        r.versionNo = card.currentVersion;
        r.stage = ReviewStage.WITHDRAWAL.name();
        r.reviewer = operator;
        r.opinion = reason;
        r.outcome = ReviewOutcome.WITHDRAWN.name();
        r.voided = false;
        r.createdAt = now;
        reviewRepository.insert(r);
        return true;
    }

    /**
     * 终审归档。
     */
    public boolean archive(CardPo card, String reviewer, String opinion) {
        LocalDateTime now = timeProvider.nowUtc();
        int rows = cardRepository.updateStatusOnly(card.cardNumber, "ARCHIVED", now, "APPROVED");
        if (rows == 0) {
            return false;
        }
        ReviewPo r = new ReviewPo();
        r.cardNumber = card.cardNumber;
        r.versionNo = card.currentVersion;
        r.stage = ReviewStage.FINAL.name();
        r.reviewer = reviewer;
        r.opinion = opinion;
        r.outcome = ReviewOutcome.APPROVED.name();
        r.voided = false;
        r.createdAt = now;
        reviewRepository.insert(r);
        return true;
    }

    /** 初报去重竞争落败后，由编排层补记 DUPLICATE 幂等结果。 */
    public void recordDuplicateInitial(String requestId, String fingerprint,
                                       String cardNumber, int versionNo, LocalDateTime now) {
        recordIdempotent(requestId, "INITIAL", fingerprint, "DUPLICATE", cardNumber, versionNo, now);
    }

    /** 发起特殊订正（调用方事务内）。 */
    public void createSpecial(SpecialCorrectionRequest req, String fingerprint,
                              LocalDateTime diagnosisUtc, boolean timely) {
        LocalDateTime now = timeProvider.nowUtc();
        SpecialCorrectionPo po = new SpecialCorrectionPo();
        po.requestId = req.requestId();
        po.cardNumber = req.cardNumber();
        po.fingerprint = fingerprint;
        po.diseaseCode = req.diseaseCode();
        po.diagnosisType = req.diagnosisType();
        po.onsetDate = req.onsetDate();
        po.diagnosisTime = diagnosisUtc;
        po.timely = timely;
        po.lateReason = trimToNull(req.lateReason());
        po.notes = req.notes();
        po.status = "PENDING";
        po.createdAt = now;
        specialRepository.insert(po);
    }

    /**
     * 特殊订正双人确认（调用方事务内）；行锁串行化同一请求的并发确认。
     *
     * @return 生效后新版本号；仍待确认返回空
     */
    public Integer confirmSpecial(String requestId, String reviewer, String opinion) {
        SpecialCorrectionPo po = specialRepository.findForUpdate(requestId);
        if (po == null) {
            throw new BusinessException("SPECIAL_NOT_FOUND",
                    "特殊订正不存在：" + requestId, org.springframework.http.HttpStatus.NOT_FOUND);
        }
        if ("APPLIED".equals(po.status)) {
            return po.appliedVersionNo;
        }
        if (reviewer.equals(po.confirmer1) || reviewer.equals(po.confirmer2)) {
            // 同一审核员重复确认不增加有效确认人数。
            return null;
        }
        if (po.confirmer1 == null) {
            specialRepository.setConfirmer1(requestId, reviewer);
            writeSpecialReview(po, reviewer, opinion);
            return null;
        }
        if (po.confirmer2 == null) {
            int rows = specialRepository.setConfirmer2(requestId, reviewer);
            if (rows == 0) {
                return null;
            }
            writeSpecialReview(po, reviewer, opinion);
            return applySpecial(po);
        }
        return null;
    }

    /**
     * 双人齐备：在归档卡上追加新版本并标记生效。先锁卡行，防止多个特殊订正并发串版本。
     */
    private Integer applySpecial(SpecialCorrectionPo po) {
        LocalDateTime now = timeProvider.nowUtc();
        CardPo card = cardRepository.findForUpdate(po.cardNumber);
        if (card == null) {
            throw new BusinessException("CARD_NOT_FOUND",
                    "卡片不存在：" + po.cardNumber, org.springframework.http.HttpStatus.NOT_FOUND);
        }
        if (!"ARCHIVED".equals(card.status)) {
            throw new BusinessException("SPECIAL_REQUIRES_ARCHIVED",
                    "特殊订正仅对已归档卡片生效", org.springframework.http.HttpStatus.CONFLICT);
        }
        cardRepository.findActiveDedup(card.patientId, po.diseaseCode, po.onsetDate)
                .filter(owner -> !owner.cardNumber.equals(card.cardNumber))
                .ifPresent(owner -> {
                    throw new BusinessException("DEDUP_CONFLICT",
                            "订正后的患者+病种+发病日期已存在有效卡：" + owner.cardNumber,
                            org.springframework.http.HttpStatus.CONFLICT);
                });
        CardVersionPo latest = versionRepository.findLatest(po.cardNumber);
        int newVersionNo = latest.versionNo + 1;
        boolean changed = !po.diseaseCode.equals(latest.diseaseCode)
                || !po.diagnosisType.equals(latest.diagnosisType);

        int bumped;
        try {
            bumped = cardRepository.bumpVersionOnArchived(po.cardNumber, po.diseaseCode,
                    po.onsetDate, card.patientId, newVersionNo, now);
        } catch (DuplicateKeyException dup) {
            throw new BusinessException("DEDUP_CONFLICT",
                    "订正后的患者+病种+发病日期已被其他有效卡占用",
                    org.springframework.http.HttpStatus.CONFLICT);
        }
        if (bumped == 0) {
            throw new BusinessException("SPECIAL_CONCURRENT",
                    "特殊订正并发冲突，请重试", org.springframework.http.HttpStatus.CONFLICT);
        }

        CardVersionPo v = new CardVersionPo();
        v.cardNumber = po.cardNumber;
        v.versionNo = newVersionNo;
        v.diseaseCode = po.diseaseCode;
        v.diagnosisType = po.diagnosisType;
        v.onsetDate = po.onsetDate;
        v.diagnosisTime = po.diagnosisTime;
        v.submittedAt = now;
        v.deadline = ReportingRules.deadline(
                diseaseOrThrow(po.diseaseCode), po.diagnosisTime);
        v.timely = po.timely;
        v.lateReason = po.lateReason;
        v.facilityCode = latest.facilityCode;
        v.reporter = latest.reporter;
        v.changeKind = ChangeKind.SPECIAL.name();
        v.diseaseChanged = changed;
        v.notes = po.notes;
        v.createdAt = now;
        versionRepository.insert(v);

        int marked = specialRepository.markApplied(po.requestId, newVersionNo, now);
        if (marked == 0) {
            throw new BusinessException("SPECIAL_STATE_CHANGED",
                    "特殊订正状态已变更", org.springframework.http.HttpStatus.CONFLICT);
        }
        return newVersionNo;
    }

    private void writeSpecialReview(SpecialCorrectionPo po, String reviewer, String opinion) {
        ReviewPo r = new ReviewPo();
        r.cardNumber = po.cardNumber;
        r.versionNo = 0;
        r.stage = ReviewStage.SPECIAL.name();
        r.reviewer = reviewer;
        r.opinion = opinion;
        r.outcome = ReviewOutcome.APPROVED.name();
        r.voided = false;
        r.createdAt = timeProvider.nowUtc();
        reviewRepository.insert(r);
    }

    private DiseasePo diseaseOrThrow(String code) {
        return catalogRepository.findDisease(code)
                .orElseThrow(() -> new BusinessException("DISEASE_NOT_FOUND",
                        "病种不存在或已停用：" + code, org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY));
    }

    private void recordIdempotent(String requestId, String type, String fingerprint,
                                  String resultKind, String cardNumber, int versionNo, LocalDateTime now) {
        IdempotentRequestPo po = new IdempotentRequestPo();
        po.requestId = requestId;
        po.requestType = type;
        po.fingerprint = fingerprint;
        po.resultKind = resultKind;
        po.cardNumber = cardNumber;
        po.versionNo = versionNo;
        po.createdAt = now;
        // 主键冲突直接上抛：由事务边界统一回滚，编排层在事务外重放首个结果。
        idempotentRepository.insert(po);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
