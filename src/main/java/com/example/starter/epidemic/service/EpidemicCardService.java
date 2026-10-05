package com.example.starter.epidemic.service;

import com.example.starter.epidemic.api.ActionResponse;
import com.example.starter.epidemic.api.ArchiveRequest;
import com.example.starter.epidemic.api.AuditTrailResponse;
import com.example.starter.epidemic.api.CardResponse;
import com.example.starter.epidemic.api.CardVersionResponse;
import com.example.starter.epidemic.api.CorrectCardRequest;
import com.example.starter.epidemic.api.ReviewRequest;
import com.example.starter.epidemic.api.SpecialConfirmRequest;
import com.example.starter.epidemic.api.SpecialCorrectRequest;
import com.example.starter.epidemic.api.SpecialCorrectionResponse;
import com.example.starter.epidemic.api.SubmitCardRequest;
import com.example.starter.epidemic.api.WithdrawRequest;
import com.example.starter.epidemic.domain.AuditAction;
import com.example.starter.epidemic.domain.AuditTrailPo;
import com.example.starter.epidemic.domain.CardPo;
import com.example.starter.epidemic.domain.CardStatus;
import com.example.starter.epidemic.domain.CardVersionPo;
import com.example.starter.epidemic.domain.DiseaseCategory;
import com.example.starter.epidemic.domain.DiseasePo;
import com.example.starter.epidemic.domain.IdempotencyPo;
import com.example.starter.epidemic.domain.SpecialCorrectionPo;
import com.example.starter.epidemic.domain.SpecialStatus;
import com.example.starter.epidemic.repository.CardRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 传染病报告卡业务服务：初报/审核/订正/撤回/终审归档/特殊订正。
 *
 * <p>关键规则：</p>
 * <ul>
 *   <li>时限：诊断时刻(UTC)＋甲类 2h/乙类 24h，终点闭区间内提交为及时；迟报强制原因。</li>
 *   <li>去重：患者＋病种＋发病日期唯一有效卡（数据库唯一约束保证），重复返回已存在编号。</li>
 *   <li>幂等：requestId＋全字段指纹，同键同参重放首响，同键异参 409，不产生新版本。</li>
 *   <li>订正追加不可变版本；病种/病例类型变更重置为待审核，原初审结论作废留痕。</li>
 *   <li>初审通过与撤回并发，由条件 UPDATE 裁决唯一终态；撤回为终态。</li>
 *   <li>归档只读、重复归档幂等；订正一律拒绝，变更走双人不同审核员的特殊订正。</li>
 * </ul>
 */
@Service
public class EpidemicCardService {

    private static final String SCOPE_SUBMIT = "SUBMIT";
    private static final String SCOPE_CORRECTION = "CORRECTION";
    private static final String SCOPE_SPECIAL = "SPECIAL";

    private final CardRepository repository;
    private final ClockService clock;
    private final Fingerprinter fingerprinter;
    private final ClusterService clusterService;

    public EpidemicCardService(CardRepository repository, ClockService clock,
                               Fingerprinter fingerprinter, ClusterService clusterService) {
        this.repository = repository;
        this.clock = clock;
        this.fingerprinter = fingerprinter;
        this.clusterService = clusterService;
    }

    // ---------------- 初报 ----------------

    @Transactional
    public CardResponse submit(SubmitCardRequest request) {
        String fingerprint = fingerprinter.fingerprint(request);
        var existing = repository.findIdempotency(request.requestId(), SCOPE_SUBMIT);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint, CardResponse.class);
        }

        DiseasePo disease = repository.findDisease(request.diseaseCode())
                .orElseThrow(() -> new BusinessRuleException("病种不存在: " + request.diseaseCode()));
        if (repository.findInstitution(request.institutionCode()).isEmpty()) {
            throw new BusinessRuleException("医疗机构不存在: " + request.institutionCode());
        }

        Instant now = clock.now();
        Instant deadline = deadline(request.diagnosisTime(), disease.category());
        boolean late = isLate(now, deadline);
        if (late && isBlank(request.lateReason())) {
            throw new BusinessRuleException("已超过报告时限（迟报），必须填写迟报原因");
        }

        String dedupKey = dedupKey(request.patientId(), request.diseaseCode(), request.onsetDate());
        var duplicate = repository.findCardByDedupKey(dedupKey);
        if (duplicate.isPresent()) {
            CardResponse response = toResponse(duplicate.get(),
                    repository.findLatestVersion(duplicate.get().getCardNo()).orElseThrow(), true);
            saveIdempotency(request.requestId(), SCOPE_SUBMIT, fingerprint,
                    duplicate.get().getCardNo(), duplicate.get().getCurrentVersion(), response, now);
            return response;
        }

        // 按合成暴露场所库归并：显式编码优先，否则按现住址关键词自动匹配。
        String exposureSiteCode = clusterService.resolveExposureSite(
                request.exposureSiteCode(), request.address());

        CardPo card = new CardPo();
        card.setCardNo(newCardNo());
        card.setPatientId(request.patientId());
        card.setPatientName(request.patientName());
        card.setDiseaseCode(request.diseaseCode());
        card.setOnsetDate(request.onsetDate());
        card.setInstitutionCode(request.institutionCode());
        card.setCurrentVersion(1);
        card.setStatus(CardStatus.PENDING_REVIEW);
        card.setDedupKey(dedupKey);
        card.setExposureSiteCode(exposureSiteCode);
        card.setCreatedAt(now);
        card.setUpdatedAt(now);

        CardVersionPo version = new CardVersionPo(
                null, card.getCardNo(), 1, "INITIAL",
                disease.code(), disease.name(), disease.category().name(),
                request.caseType(), request.patientId(), request.patientName(),
                request.age(), request.gender(), request.onsetDate(), request.diagnosisTime(),
                request.institutionCode(), request.reporter(), request.address(), request.phone(),
                late, late ? request.lateReason() : null, request.requestId(), now);

        try {
            repository.insertCard(card);
            repository.insertVersion(version);
            repository.insertTrail(card.getCardNo(), 1, AuditAction.SUBMIT, request.reporter(),
                    "初报提交" + (late ? "（迟报）" : ""), now);
        } catch (DuplicateKeyException e) {
            // 并发重复报告：唯一约束裁决，不新建卡片，返回已存在卡片。
            CardPo winner = repository.findCardByDedupKey(dedupKey)
                    .orElseThrow(() -> new BusinessRuleException("报告卡状态冲突，请重试"));
            CardResponse response = toResponse(winner,
                    repository.findLatestVersion(winner.getCardNo()).orElseThrow(), true);
            saveIdempotency(request.requestId(), SCOPE_SUBMIT, fingerprint,
                    winner.getCardNo(), winner.getCurrentVersion(), response, now);
            return response;
        }

        CardResponse response = toResponse(card, version, false);
        saveIdempotency(request.requestId(), SCOPE_SUBMIT, fingerprint,
                card.getCardNo(), 1, response, now);
        // 新卡落库后按暴露场所归并：同事务生成聚集事件与自动协查单。
        clusterService.onCardSubmitted(card);
        return response;
    }

    // ---------------- 订正 ----------------

    @Transactional
    public CardResponse correct(CorrectCardRequest request) {
        // 先锁卡，串行化同卡订正/审核/撤回，再判定幂等与状态。
        CardPo card = repository.findCardForUpdate(request.cardNo())
                .orElseThrow(() -> new NotFoundException("报告卡不存在: " + request.cardNo()));

        String fingerprint = fingerprinter.fingerprint(request);
        var existing = repository.findIdempotency(request.requestId(), SCOPE_CORRECTION);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint, CardResponse.class);
        }

        if (card.getStatus() == CardStatus.ARCHIVED) {
            throw new BusinessRuleException("卡片已终审归档（只读），订正请求拒绝；疫情核实确需变更请走特殊订正流程");
        }
        if (card.getStatus() == CardStatus.WITHDRAWN) {
            throw new BusinessRuleException("卡片已撤回（终态），不可订正");
        }

        CardVersionPo latest = repository.findLatestVersionForUpdate(request.cardNo())
                .orElseThrow(() -> new IllegalStateException("卡片缺少版本数据: " + request.cardNo()));

        String newDiseaseCode = request.diseaseCode() != null ? request.diseaseCode() : latest.diseaseCode();
        DiseasePo disease = repository.findDisease(newDiseaseCode)
                .orElseThrow(() -> new BusinessRuleException("病种不存在: " + newDiseaseCode));
        String newInstitution = request.institutionCode() != null
                ? request.institutionCode() : latest.institutionCode();
        if (repository.findInstitution(newInstitution).isEmpty()) {
            throw new BusinessRuleException("医疗机构不存在: " + newInstitution);
        }
        String newCaseType = request.caseType() != null ? request.caseType() : latest.caseType();
        LocalDate newOnsetDate = request.onsetDate() != null ? request.onsetDate() : latest.onsetDate();
        Instant newDiagnosisTime = request.diagnosisTime() != null
                ? request.diagnosisTime() : latest.diagnosisTime();

        Instant now = clock.now();
        Instant deadline = deadline(newDiagnosisTime, disease.category());
        boolean late = isLate(now, deadline);
        if (late && isBlank(request.lateReason())) {
            throw new BusinessRuleException("订正后版本已超过报告时限（迟报），必须填写迟报原因");
        }

        boolean diseaseChanged = !newDiseaseCode.equals(latest.diseaseCode())
                || !newCaseType.equals(latest.caseType());
        int newVersionNo = latest.versionNo() + 1;

        CardVersionPo next = new CardVersionPo(
                null, card.getCardNo(), newVersionNo, "CORRECTION",
                disease.code(), disease.name(), disease.category().name(),
                newCaseType, latest.patientId(),
                request.patientName() != null ? request.patientName() : latest.patientName(),
                request.age() != null ? request.age() : latest.age(),
                request.gender() != null ? request.gender() : latest.gender(),
                newOnsetDate, newDiagnosisTime, newInstitution,
                request.reporter() != null ? request.reporter() : latest.reporter(),
                request.address() != null ? request.address() : latest.address(),
                request.phone() != null ? request.phone() : latest.phone(),
                late, late ? request.lateReason() : null,
                request.requestId(), now);

        String newDedupKey = dedupKey(next.patientId(), newDiseaseCode, newOnsetDate);
        try {
            repository.insertVersion(next);
            if (diseaseChanged) {
                int rows = repository.compareAndUpdateCard(card.getCardNo(), card.getStatus(),
                        CardStatus.PENDING_REVIEW, newVersionNo, newDiseaseCode, newDedupKey, now);
                if (rows == 0) {
                    throw new BusinessRuleException("卡片状态已被并发改变，订正失败");
                }
            } else {
                int rows = repository.compareAndUpdateCard(card.getCardNo(), card.getStatus(),
                        card.getStatus(), newVersionNo,
                        newDiseaseCode.equals(latest.diseaseCode()) ? null : newDiseaseCode,
                        newDedupKey.equals(card.getDedupKey()) ? null : newDedupKey, now);
                if (rows == 0) {
                    throw new BusinessRuleException("卡片状态已被并发改变，订正失败");
                }
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessRuleException("订正后的患者＋病种＋发病日期与其他有效卡重复，拒绝订正");
        }

        repository.insertTrail(card.getCardNo(), newVersionNo, AuditAction.CORRECTED,
                next.reporter(), "订正生成版本 " + newVersionNo
                        + (diseaseChanged ? "；病种/病例类型变更，重新起算审核" : ""), now);
        if (diseaseChanged && card.getStatus() == CardStatus.PENDING_FINAL) {
            String priorReviewer = repository.findTrails(card.getCardNo()).stream()
                    .filter(t -> t.action() == AuditAction.REVIEW_APPROVED)
                    .reduce((first, second2) -> second2)
                    .map(AuditTrailPo::operator)
                    .orElse("原审核员");
            repository.insertTrail(card.getCardNo(), latest.versionNo(), AuditAction.REVIEW_VOIDED,
                    next.reporter(), "病种/病例类型变更，原初审通过结论作废（原审核员：" + priorReviewer + "）", now);
        }

        card.setStatus(diseaseChanged ? CardStatus.PENDING_REVIEW : card.getStatus());
        card.setCurrentVersion(newVersionNo);
        card.setDiseaseCode(newDiseaseCode);
        CardResponse response = toResponse(card, next, false);
        saveIdempotency(request.requestId(), SCOPE_CORRECTION, fingerprint,
                card.getCardNo(), newVersionNo, response, now);
        return response;
    }

    // ---------------- 初审 / 撤回 / 终审 ----------------

    @Transactional
    public ActionResponse review(String cardNo, ReviewRequest request) {
        CardPo card = repository.findCardForUpdate(cardNo)
                .orElseThrow(() -> new NotFoundException("报告卡不存在: " + cardNo));
        if (card.getStatus() != CardStatus.PENDING_REVIEW) {
            throw new BusinessRuleException("当前状态[" + card.getStatus() + "]不允许初审；仅待审核卡片可初审");
        }
        int versionNo = card.getCurrentVersion();
        int rows = repository.compareAndUpdateCard(cardNo, CardStatus.PENDING_REVIEW,
                CardStatus.PENDING_FINAL, null, null, null, clock.now());
        if (rows == 0) {
            throw new BusinessRuleException("卡片已被并发撤回或状态变更，初审未生效");
        }
        repository.insertTrail(cardNo, versionNo, AuditAction.REVIEW_APPROVED, request.reviewer(),
                isBlank(request.comment()) ? "初审通过" : request.comment(), clock.now());
        return new ActionResponse(cardNo, CardStatus.PENDING_FINAL, "初审通过，进入待终审");
    }

    @Transactional
    public ActionResponse withdraw(String cardNo, WithdrawRequest request) {
        CardPo card = repository.findCardForUpdate(cardNo)
                .orElseThrow(() -> new NotFoundException("报告卡不存在: " + cardNo));
        if (card.getStatus() != CardStatus.PENDING_REVIEW) {
            throw new BusinessRuleException("当前状态[" + card.getStatus() + "]不允许撤回；仅审核通过前可撤回");
        }
        Instant now = clock.now();
        int rows = repository.withdrawCard(cardNo, now);
        if (rows == 0) {
            throw new BusinessRuleException("卡片已被并发初审通过，撤回未生效（唯一终态）");
        }
        repository.insertTrail(cardNo, card.getCurrentVersion(), AuditAction.WITHDRAWN,
                request.operator(), isBlank(request.reason()) ? "审核通过前撤回（终态）" : request.reason(), now);
        return new ActionResponse(cardNo, CardStatus.WITHDRAWN, "卡片已撤回，终态不可恢复");
    }

    @Transactional
    public ActionResponse archive(String cardNo, ArchiveRequest request) {
        CardPo card = repository.findCardForUpdate(cardNo)
                .orElseThrow(() -> new NotFoundException("报告卡不存在: " + cardNo));
        if (card.getStatus() == CardStatus.ARCHIVED) {
            return new ActionResponse(cardNo, CardStatus.ARCHIVED, "卡片已归档，重复归档幂等成功");
        }
        if (card.getStatus() != CardStatus.PENDING_FINAL) {
            throw new BusinessRuleException("当前状态[" + card.getStatus() + "]不允许终审归档；仅初审通过卡片可归档");
        }
        Instant now = clock.now();
        int rows = repository.compareAndUpdateCard(cardNo, CardStatus.PENDING_FINAL,
                CardStatus.ARCHIVED, null, null, null, now);
        if (rows == 0) {
            throw new BusinessRuleException("卡片状态已被并发改变，归档未生效");
        }
        repository.insertTrail(cardNo, card.getCurrentVersion(), AuditAction.ARCHIVED,
                request.reviewer(), isBlank(request.comment()) ? "终审通过，归档（只读）" : request.comment(), now);
        return new ActionResponse(cardNo, CardStatus.ARCHIVED, "终审通过，卡片归档只读");
    }

    // ---------------- 特殊订正（双人） ----------------

    @Transactional
    public SpecialCorrectionResponse startSpecial(SpecialCorrectRequest request) {
        String fingerprint = fingerprinter.fingerprint(request);
        var existing = repository.findIdempotency(request.requestId(), SCOPE_SPECIAL);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint, SpecialCorrectionResponse.class);
        }

        CardPo card = repository.findCardForUpdate(request.cardNo())
                .orElseThrow(() -> new NotFoundException("报告卡不存在: " + request.cardNo()));
        if (card.getStatus() != CardStatus.ARCHIVED) {
            throw new BusinessRuleException("仅已归档卡片可发起特殊订正；当前状态[" + card.getStatus() + "]");
        }
        if (request.diseaseCode() != null && repository.findDisease(request.diseaseCode()).isEmpty()) {
            throw new BusinessRuleException("病种不存在: " + request.diseaseCode());
        }

        Instant now = clock.now();
        SpecialCorrectionPo po = new SpecialCorrectionPo();
        po.setCardNo(request.cardNo());
        po.setRequestId(request.requestId());
        po.setDiseaseCode(request.diseaseCode());
        po.setCaseType(request.caseType());
        po.setDiagnosisTime(request.diagnosisTime());
        po.setReason(request.reason());
        po.setInitiator(request.initiator());
        po.setStatus(SpecialStatus.WAITING_SECOND);
        po.setCreatedAt(now);

        long id;
        try {
            id = repository.insertSpecial(po);
        } catch (DuplicateKeyException e) {
            SpecialCorrectionPo winner = repository.findSpecialByRequestId(request.requestId())
                    .orElseThrow(() -> new BusinessRuleException("特殊订正请求冲突，请重试"));
            return toSpecialResponse(winner);
        }
        repository.insertTrail(request.cardNo(), card.getCurrentVersion(),
                AuditAction.SPECIAL_STARTED, request.initiator(),
                "归档后疫情核实发起特殊订正，等待两名不同审核员确认：" + request.reason(), now);

        SpecialCorrectionPo saved = repository.findSpecialForUpdate(id).orElseThrow();
        SpecialCorrectionResponse response = toSpecialResponse(saved);
        saveIdempotency(request.requestId(), SCOPE_SPECIAL, fingerprint,
                request.cardNo(), card.getCurrentVersion(), response, now);
        return response;
    }

    @Transactional
    public SpecialCorrectionResponse confirmSpecial(SpecialConfirmRequest request) {
        long specialId = request.specialId();
        String reviewer = request.reviewer();
        SpecialCorrectionPo special = repository.findSpecialForUpdate(specialId)
                .orElseThrow(() -> new NotFoundException("特殊订正流程不存在: " + specialId));
        if (special.getStatus() == SpecialStatus.EFFECTIVE) {
            return toSpecialResponse(special);
        }

        Instant now = clock.now();
        if (special.getFirstReviewer() == null) {
            int rows = repository.updateSpecialFirst(special.getId(), reviewer, now);
            if (rows == 0) {
                special = repository.findSpecialForUpdate(specialId).orElseThrow();
                return toSpecialResponse(special);
            }
            repository.insertTrail(special.getCardNo(),
                    currentVersionOf(special.getCardNo()),
                    AuditAction.SPECIAL_FIRST_CONFIRMED, reviewer,
                    "特殊订正第一名审核员确认，尚需另一名审核员确认", now);
            special.setFirstReviewer(reviewer);
            special.setFirstAt(now);
            return toSpecialResponse(special);
        }

        if (special.getFirstReviewer().equals(reviewer)) {
            throw new BusinessRuleException("特殊订正须两名不同审核员先后确认；第二名审核员不能与第一人相同");
        }

        // 第二名不同审核员确认：锁定卡片并生效新版本（卡片保持 ARCHIVED 只读终态）。
        CardPo card = repository.findCardForUpdate(special.getCardNo()).orElseThrow();
        if (card.getStatus() != CardStatus.ARCHIVED) {
            throw new BusinessRuleException("卡片状态异常，特殊订正无法生效");
        }
        CardVersionPo latest = repository.findLatestVersionForUpdate(card.getCardNo()).orElseThrow();

        String newDiseaseCode = special.getDiseaseCode() != null
                ? special.getDiseaseCode() : latest.diseaseCode();
        DiseasePo disease = repository.findDisease(newDiseaseCode)
                .orElseThrow(() -> new BusinessRuleException("病种不存在: " + newDiseaseCode));
        String newCaseType = special.getCaseType() != null ? special.getCaseType() : latest.caseType();
        Instant newDiagnosisTime = special.getDiagnosisTime() != null
                ? special.getDiagnosisTime() : latest.diagnosisTime();
        int newVersionNo = latest.versionNo() + 1;

        CardVersionPo next = new CardVersionPo(
                null, card.getCardNo(), newVersionNo, "SPECIAL",
                disease.code(), disease.name(), disease.category().name(),
                newCaseType, latest.patientId(), latest.patientName(), latest.age(), latest.gender(),
                latest.onsetDate(), newDiagnosisTime, latest.institutionCode(), latest.reporter(),
                latest.address(), latest.phone(),
                // 特殊订正为归档后疫情核实更正，迟报属性沿用作废前最新版本判定，不重新起算报告时限。
                latest.late(), latest.lateReason(),
                special.getRequestId(), now);

        String newDedupKey = dedupKey(next.patientId(), newDiseaseCode, next.onsetDate());
        repository.insertVersion(next);
        int cardRows;
        try {
            cardRows = repository.updateCardVersionInfo(card.getCardNo(), newVersionNo,
                    newDiseaseCode, newDedupKey, now);
        } catch (DuplicateKeyException e) {
            throw new BusinessRuleException("特殊订正后的患者＋病种＋发病日期与其他有效卡重复，订正拒绝");
        }
        if (cardRows == 0) {
            throw new BusinessRuleException("卡片已非归档状态，特殊订正生效失败");
        }
        int specialRows = repository.updateSpecialEffective(special.getId(), reviewer,
                now, newVersionNo);
        if (specialRows == 0) {
            throw new BusinessRuleException("特殊订正流程已被并发处理");
        }
        repository.insertTrail(card.getCardNo(), newVersionNo,
                AuditAction.SPECIAL_SECOND_CONFIRMED, reviewer,
                "第二名（不同）审核员确认，特殊订正生效，生成版本 " + newVersionNo
                        + "；核实结论：" + special.getReason(), now);

        special.setSecondReviewer(reviewer);
        special.setSecondAt(now);
        special.setStatus(SpecialStatus.EFFECTIVE);
        special.setEffectiveVersion(newVersionNo);
        return toSpecialResponse(special);
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public CardResponse getCard(String cardNo) {
        CardPo card = repository.findCard(cardNo)
                .orElseThrow(() -> new NotFoundException("报告卡不存在: " + cardNo));
        CardVersionPo latest = repository.findLatestVersion(cardNo)
                .orElseThrow(() -> new IllegalStateException("卡片缺少版本数据: " + cardNo));
        return toResponse(card, latest, false);
    }

    @Transactional(readOnly = true)
    public List<AuditTrailResponse> getTrails(String cardNo) {
        if (repository.findCard(cardNo).isEmpty()) {
            throw new NotFoundException("报告卡不存在: " + cardNo);
        }
        return repository.findTrails(cardNo).stream()
                .map(t -> new AuditTrailResponse(t.versionNo(), t.action(), t.operator(),
                        t.detail(), t.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CardVersionResponse> getVersions(String cardNo) {
        if (repository.findCard(cardNo).isEmpty()) {
            throw new NotFoundException("报告卡不存在: " + cardNo);
        }
        return repository.findVersions(cardNo).stream()
                .map(v -> new CardVersionResponse(v.versionNo(), v.changeType(), v.diseaseCode(),
                        v.diseaseName(), v.category(), v.caseType(), v.patientName(),
                        v.onsetDate(), v.diagnosisTime(), v.late(), v.lateReason(),
                        v.requestId(), v.submittedAt()))
                .toList();
    }

    // ---------------- 内部辅助 ----------------

    private int currentVersionOf(String cardNo) {
        return repository.findCard(cardNo).map(CardPo::getCurrentVersion).orElse(0);
    }

    private <T> T replayOrConflict(IdempotencyPo record, String fingerprint, Class<T> type) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(
                    "requestId=" + record.requestId() + " 已用于不同参数的请求（409 冲突），本次请求被拒绝且不产生新版本");
        }
        return fingerprinter.fromJson(record.responseJson(), type);
    }

    private void saveIdempotency(String requestId, String scope, String fingerprint, String cardNo,
                                 Integer versionNo, Object response, Instant now) {
        try {
            repository.insertIdempotency(new IdempotencyPo(requestId, scope, fingerprint, cardNo,
                    versionNo, fingerprinter.toJson(response), now));
        } catch (DuplicateKeyException e) {
            // 并发同键请求已由另一事务落库：以库中首次结果为准。
            var saved = repository.findIdempotency(requestId, scope);
            if (saved.isPresent() && saved.get().fingerprint().equals(fingerprint)) {
                return;
            }
            throw new IdempotencyConflictException("requestId=" + requestId + " 并发请求参数不一致（409 冲突）");
        }
    }

    private CardResponse toResponse(CardPo card, CardVersionPo v, boolean duplicate) {
        return new CardResponse(
                duplicate,
                card.getCardNo(),
                v.versionNo(),
                card.getStatus(),
                v.diseaseCode(),
                v.diseaseName(),
                v.category(),
                v.caseType(),
                v.patientId(),
                v.patientName(),
                v.age(),
                v.gender(),
                v.onsetDate(),
                v.diagnosisTime(),
                v.institutionCode(),
                v.reporter(),
                v.address(),
                v.phone(),
                v.late(),
                v.lateReason(),
                deadline(v.diagnosisTime(), DiseaseCategory.valueOf(v.category())),
                v.submittedAt(),
                card.getUpdatedAt(),
                card.getExposureSiteCode());
    }

    private SpecialCorrectionResponse toSpecialResponse(SpecialCorrectionPo po) {
        return new SpecialCorrectionResponse(
                po.getId(), po.getCardNo(), po.getStatus(),
                po.getFirstReviewer(), po.getFirstAt(),
                po.getSecondReviewer(), po.getSecondAt(),
                po.getEffectiveVersion(), po.getReason());
    }

    /** 报告时限终点（含端点）：诊断时刻＋分类时限。 */
    private Instant deadline(Instant diagnosisTime, DiseaseCategory category) {
        return diagnosisTime.plus(Duration.ofHours(category.deadlineHours()));
    }

    /** 迟报判定：提交时刻严格晚于时限终点为迟报；恰在终点为及时。 */
    private boolean isLate(Instant submittedAt, Instant deadline) {
        return submittedAt.isAfter(deadline);
    }

    /** 有效卡去重指纹：患者＋病种＋发病日期。 */
    private String dedupKey(String patientId, String diseaseCode, LocalDate onsetDate) {
        String raw = patientId + "|" + diseaseCode + "|" + onsetDate;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String newCardNo() {
        return "C" + Long.toHexString(clock.now().toEpochMilli())
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
