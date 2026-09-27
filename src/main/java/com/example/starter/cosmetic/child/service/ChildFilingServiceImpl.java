package com.example.starter.cosmetic.child.service;

import com.example.starter.cosmetic.child.domain.AgeBand;
import com.example.starter.cosmetic.child.domain.ChildAgeRule;
import com.example.starter.cosmetic.child.domain.ChildFilingRecord;
import com.example.starter.cosmetic.child.domain.ChildFilingStatus;
import com.example.starter.cosmetic.child.domain.ChildLabel;
import com.example.starter.cosmetic.child.domain.ChildRevisionRecord;
import com.example.starter.cosmetic.child.domain.ChildRevisionStatus;
import com.example.starter.cosmetic.child.domain.ChildRevisionType;
import com.example.starter.cosmetic.child.domain.ChildSafetyReport;
import com.example.starter.cosmetic.child.port.ChildCatalogPort;
import com.example.starter.cosmetic.child.port.ChildFilingPort;
import com.example.starter.cosmetic.child.port.ChildSafetyReportPort;
import com.example.starter.cosmetic.child.web.dto.AgeRuleResponse;
import com.example.starter.cosmetic.child.web.dto.ChildChangeRequest;
import com.example.starter.cosmetic.child.web.dto.ChildCorrectionRequest;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResponse;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResultResponse;
import com.example.starter.cosmetic.child.web.dto.ChildLabelRequest;
import com.example.starter.cosmetic.child.web.dto.ChildRevisionResponse;
import com.example.starter.cosmetic.child.web.dto.ChildSafetyReportRequest;
import com.example.starter.cosmetic.child.web.dto.ChildSubmitRequest;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.domain.ReviewDetail;
import com.example.starter.cosmetic.port.FilingPort;
import com.example.starter.cosmetic.port.IdempotencyPort;
import com.example.starter.cosmetic.port.IdempotencyRow;
import com.example.starter.cosmetic.service.ConflictException;
import com.example.starter.cosmetic.service.NotFoundException;
import com.example.starter.cosmetic.support.FingerprintSupport;
import com.example.starter.cosmetic.support.JsonSupport;
import com.example.starter.cosmetic.web.dto.FormulationItemRequest;
import com.example.starter.cosmetic.web.dto.ReviewDetailResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 儿童化妆品分龄备案领域服务默认实现。
 *
 * <p>写操作幂等：指纹覆盖 requestId 与全部业务字段（含 expectedVersion、集合与精确时刻），
 * 同键同参返回首次冻结结果（idempotentReplay=true），同键异参 409；
 * 业务/参数失败抛异常回滚，幂等键不被占用。业务、版本与幂等结果同事务。</p>
 */
@Service
public class ChildFilingServiceImpl implements ChildFilingService {

    private static final String OP_CHILD_SUBMIT = "CHILD_SUBMIT";
    private static final String OP_CHILD_CORRECT = "CHILD_CORRECT";
    private static final String OP_CHILD_CHANGE = "CHILD_CHANGE";
    private static final String KIND_SIMPLE = "SIMPLE";
    private static final String KIND_SUBSTANTIVE = "SUBSTANTIVE";
    private static final int PUBLIC_NOTICE_DAYS = 10;
    private static final long IDEMPOTENCY_WAIT_NANOS = 10_000_000_000L;
    private static final long IDEMPOTENCY_WAIT_STEP_MS = 20L;

    private final ChildFilingPort childFilingPort;
    private final ChildCatalogPort catalogPort;
    private final ChildSafetyReportPort safetyReportPort;
    private final IdempotencyPort idempotencyPort;
    private final FilingPort evidencePort;
    private final ChildPublicityEffector publicityEffector;
    private final Clock clock;
    private final ChildReviewEvaluator reviewEvaluator;

    public ChildFilingServiceImpl(ChildFilingPort childFilingPort,
                                  ChildCatalogPort catalogPort,
                                  ChildSafetyReportPort safetyReportPort,
                                  IdempotencyPort idempotencyPort,
                                  FilingPort evidencePort,
                                  ChildPublicityEffector publicityEffector,
                                  Clock clock) {
        this.childFilingPort = childFilingPort;
        this.catalogPort = catalogPort;
        this.safetyReportPort = safetyReportPort;
        this.idempotencyPort = idempotencyPort;
        this.evidencePort = evidencePort;
        this.publicityEffector = publicityEffector;
        this.clock = clock;
        this.reviewEvaluator = new ChildReviewEvaluator(catalogPort);
    }

    private Instant now() {
        return clock.instant();
    }

    // ============================ 儿童备案提交 ============================

    @Override
    @Transactional
    public ChildFilingResultResponse submit(ChildSubmitRequest request) {
        Instant now = now();
        Instant submittedAt = request.submittedAtUtc() == null ? now : request.submittedAtUtc();
        Map<String, Object> fingerprintFields = new LinkedHashMap<>();
        fingerprintFields.put("requestId", request.requestId());
        fingerprintFields.put("productCode", request.productCode());
        fingerprintFields.put("productName", request.productName());
        fingerprintFields.put("ageLowerMonths", request.ageLowerMonths());
        fingerprintFields.put("ageUpperMonths", request.ageUpperMonths());
        fingerprintFields.put("claims", request.claims());
        fingerprintFields.put("formulation", request.formulation());
        fingerprintFields.put("safetyReports", request.safetyReports() == null
                ? List.of() : request.safetyReports());
        fingerprintFields.put("labels", request.labels() == null ? List.of() : request.labels());
        fingerprintFields.put("packagingText", request.packagingText());
        fingerprintFields.put("submittedAtUtc", request.submittedAtUtc());
        String fingerprint = FingerprintSupport.sha256(fingerprintFields);

        Optional<ChildFilingResultResponse> replay =
                resolveIdempotency(request.requestId(), OP_CHILD_SUBMIT, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        ChildAgeRule rule = catalogPort.activeAgeRule();
        validateMaterials(rule, request.ageLowerMonths(), request.ageUpperMonths(),
                request.safetyReports(), request.labels());

        if (childFilingPort.findFilingByProduct(request.productCode()).isPresent()) {
            throw new BusinessRejectionException("CHILD_FILING_EXISTS",
                    "同一产品已存在儿童备案，须通过补正/变更办理: " + request.productCode());
        }

        List<FormulationIngredient> formulation = toFormulation(request.formulation());
        List<String> claims = List.copyOf(request.claims());
        List<ChildSafetyReport> reports = registerReports(request.safetyReports());
        List<ChildLabel> labels = toLabels(request.labels());

        String filingNo = allocateFilingNo();
        ChildReviewEvaluator.Result review = reviewEvaluator.evaluate(
                request.ageLowerMonths(), request.ageUpperMonths(), claims, formulation,
                reports, labels, rule, now, filingNo, 1);

        boolean passed = review.passed();
        ChildFilingStatus filingStatus = passed
                ? ChildFilingStatus.IN_PUBLICITY : ChildFilingStatus.REVIEW_REJECTED;
        ChildRevisionStatus revisionStatus = passed
                ? ChildRevisionStatus.PUBLIC : ChildRevisionStatus.REJECTED;
        Instant acceptedAt = passed ? submittedAt : null;
        Instant expiresAt = passed ? publicExpiry(submittedAt) : null;

        ChildFilingRecord filing = new ChildFilingRecord(null, filingNo, request.productCode(),
                request.productName(), 1, filingStatus, 1, request.requestId(), now, now);
        childFilingPort.insertFiling(filing);
        ChildRevisionRecord revision = new ChildRevisionRecord(null, filingNo, 1,
                ChildRevisionType.INITIAL, revisionStatus,
                request.ageLowerMonths(), request.ageUpperMonths(), claims, formulation,
                reports, labels, rule.ruleVersion(), labelDigest(labels), request.packagingText(),
                passed ? null : String.join("；", review.reasons()), request.requestId(),
                submittedAt, acceptedAt, expiresAt, null);
        childFilingPort.insertRevision(revision);
        evidencePort.saveReviews(review.details());

        ChildFilingRecord savedFiling = childFilingPort.findFiling(filingNo).orElseThrow();
        ChildRevisionRecord savedRevision = childFilingPort.findRevision(filingNo, 1).orElseThrow();
        ChildFilingResultResponse response =
                buildResult(savedFiling, savedRevision, review.details(), false, now);
        idempotencyPort.complete(request.requestId(), 200, filingStatus.name(), filingNo,
                JsonSupport.toJson(response), now);
        return response;
    }

    // ============================ 材料补正 ============================

    @Override
    @Transactional
    public ChildFilingResultResponse correct(String filingNo, ChildCorrectionRequest request) {
        Instant now = now();
        Instant submittedAt = request.submittedAtUtc() == null ? now : request.submittedAtUtc();
        Map<String, Object> fingerprintFields = new LinkedHashMap<>();
        fingerprintFields.put("requestId", request.requestId());
        fingerprintFields.put("filingNo", filingNo);
        fingerprintFields.put("expectedVersion", request.expectedVersion());
        fingerprintFields.put("ageLowerMonths", request.ageLowerMonths());
        fingerprintFields.put("ageUpperMonths", request.ageUpperMonths());
        fingerprintFields.put("claims", request.claims());
        fingerprintFields.put("formulation", request.formulation());
        fingerprintFields.put("safetyReports", request.safetyReports() == null
                ? List.of() : request.safetyReports());
        fingerprintFields.put("labels", request.labels() == null ? List.of() : request.labels());
        fingerprintFields.put("packagingText", request.packagingText());
        fingerprintFields.put("submittedAtUtc", request.submittedAtUtc());
        String fingerprint = FingerprintSupport.sha256(fingerprintFields);

        Optional<ChildFilingResultResponse> replay =
                resolveIdempotency(request.requestId(), OP_CHILD_CORRECT, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        ChildFilingRecord filing = childFilingPort.findFiling(filingNo)
                .orElseThrow(() -> new NotFoundException("儿童备案不存在: " + filingNo));
        ChildAgeRule rule = catalogPort.activeAgeRule();
        validateMaterials(rule, request.ageLowerMonths(), request.ageUpperMonths(),
                request.safetyReports(), request.labels());

        ChildRevisionRecord latest = childFilingPort
                .findRevision(filingNo, filing.currentRevisionNo())
                .orElseThrow(() -> new IllegalStateException("最新修订不存在: " + filingNo));
        if (latest.status() != ChildRevisionStatus.REJECTED
                && latest.status() != ChildRevisionStatus.PUBLIC) {
            throw new BusinessRejectionException("CORRECTION_NOT_ALLOWED",
                    "仅审查不通过或公示中的最新修订可补正；已生效备案请走变更接口");
        }

        List<FormulationIngredient> formulation = toFormulation(request.formulation());
        List<String> claims = List.copyOf(request.claims());
        List<ChildSafetyReport> reports = registerReports(request.safetyReports());
        List<ChildLabel> labels = toLabels(request.labels());

        int newRevisionNo = latest.revisionNo() + 1;
        ChildReviewEvaluator.Result review = reviewEvaluator.evaluate(
                request.ageLowerMonths(), request.ageUpperMonths(), claims, formulation,
                reports, labels, rule, now, filingNo, newRevisionNo);

        boolean passed = review.passed();
        // 审查不通过且已有生效修订时，聚合保持生效（零生效不影响旧备案）
        ChildFilingStatus newFilingStatus = passed ? ChildFilingStatus.IN_PUBLICITY
                : (filing.status() == ChildFilingStatus.EFFECTIVE
                ? ChildFilingStatus.EFFECTIVE : ChildFilingStatus.REVIEW_REJECTED);
        ChildRevisionStatus revisionStatus = passed
                ? ChildRevisionStatus.PUBLIC : ChildRevisionStatus.REJECTED;
        Instant acceptedAt = passed ? submittedAt : null;
        Instant expiresAt = passed ? publicExpiry(submittedAt) : null;

        // 旧修订待审批准失效：与公示期满竞争，条件更新保证唯一结果。
        // 加锁顺序与生效执行器一致（先修订行后聚合行），避免并发死锁；
        // 后续版本检查失败时本更新随事务回滚。
        if (!childFilingPort.invalidateRevisionIfPending(latest.id(), now)) {
            throw new BusinessRejectionException("CORRECTION_LOST_TO_EXPIRY",
                    "旧修订已公示期满生效，补正不再受理，请走变更接口");
        }
        // 版本乐观锁：expectedVersion 不一致则业务拒绝（同事务回滚，幂等键不占）
        if (!childFilingPort.incrementVersionIfExpected(filingNo, request.expectedVersion(),
                filing.productName(), newFilingStatus, newRevisionNo, now)) {
            throw new BusinessRejectionException("VERSION_MISMATCH",
                    "expectedVersion=" + request.expectedVersion() + " 与当前版本不一致，请重新读取后提交");
        }

        ChildRevisionRecord revision = new ChildRevisionRecord(null, filingNo, newRevisionNo,
                ChildRevisionType.CORRECTION, revisionStatus,
                request.ageLowerMonths(), request.ageUpperMonths(), claims, formulation,
                reports, labels, rule.ruleVersion(), labelDigest(labels), request.packagingText(),
                passed ? null : String.join("；", review.reasons()), request.requestId(),
                submittedAt, acceptedAt, expiresAt, null);
        childFilingPort.insertRevision(revision);
        evidencePort.saveReviews(review.details());

        ChildFilingRecord savedFiling = childFilingPort.findFiling(filingNo).orElseThrow();
        ChildRevisionRecord savedRevision =
                childFilingPort.findRevision(filingNo, newRevisionNo).orElseThrow();
        ChildFilingResultResponse response =
                buildResult(savedFiling, savedRevision, review.details(), false, now);
        idempotencyPort.complete(request.requestId(), 200, newFilingStatus.name(), filingNo,
                JsonSupport.toJson(response), now);
        return response;
    }

    // ============================ 备案变更 ============================

    @Override
    @Transactional
    public ChildFilingResultResponse change(String filingNo, ChildChangeRequest request) {
        Instant now = now();
        Instant submittedAt = request.submittedAtUtc() == null ? now : request.submittedAtUtc();
        Map<String, Object> fingerprintFields = new LinkedHashMap<>();
        fingerprintFields.put("requestId", request.requestId());
        fingerprintFields.put("filingNo", filingNo);
        fingerprintFields.put("expectedVersion", request.expectedVersion());
        fingerprintFields.put("changeKind", request.changeKind());
        fingerprintFields.put("productName", request.productName());
        fingerprintFields.put("ageLowerMonths", request.ageLowerMonths());
        fingerprintFields.put("ageUpperMonths", request.ageUpperMonths());
        fingerprintFields.put("claims", request.claims());
        fingerprintFields.put("formulation", request.formulation());
        fingerprintFields.put("safetyReports", request.safetyReports());
        fingerprintFields.put("labels", request.labels());
        fingerprintFields.put("packagingText", request.packagingText());
        fingerprintFields.put("submittedAtUtc", request.submittedAtUtc());
        String fingerprint = FingerprintSupport.sha256(fingerprintFields);

        Optional<ChildFilingResultResponse> replay =
                resolveIdempotency(request.requestId(), OP_CHILD_CHANGE, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        ChildFilingRecord filing = childFilingPort.findFiling(filingNo)
                .orElseThrow(() -> new NotFoundException("儿童备案不存在: " + filingNo));
        if (filing.status() != ChildFilingStatus.EFFECTIVE) {
            throw new BusinessRejectionException("FILING_NOT_EFFECTIVE",
                    "仅生效备案可变更，当前状态: " + filing.status() + "（公示中或审查不通过请走补正）");
        }
        if (!KIND_SIMPLE.equals(request.changeKind()) && !KIND_SUBSTANTIVE.equals(request.changeKind())) {
            throw new IllegalArgumentException("changeKind 须为 SIMPLE 或 SUBSTANTIVE: " + request.changeKind());
        }
        ChildRevisionRecord current = childFilingPort.findEffectiveRevision(filingNo)
                .orElseThrow(() -> new IllegalStateException("生效备案缺少生效修订: " + filingNo));

        ChildAgeRule rule = catalogPort.activeAgeRule();
        String newName = request.productName() == null ? filing.productName() : request.productName();
        int newLower = request.ageLowerMonths() == null
                ? current.ageLowerMonths() : request.ageLowerMonths();
        int newUpper = request.ageUpperMonths() == null
                ? current.ageUpperMonths() : request.ageUpperMonths();
        List<String> newClaims = request.claims() == null
                ? current.claims() : List.copyOf(request.claims());
        List<FormulationIngredient> newFormulation = request.formulation() == null
                ? current.formulation() : toFormulation(request.formulation());
        List<ChildSafetyReport> newReports = request.safetyReports() == null
                ? current.safetyReports() : registerReports(request.safetyReports());
        List<ChildLabel> newLabels = request.labels() == null
                ? current.labels() : toLabels(request.labels());
        String newPackaging = request.packagingText() == null
                ? current.packagingText() : request.packagingText();

        validateMaterials(rule, newLower, newUpper, request.safetyReports(), request.labels());

        boolean nameChanged = !newName.equals(filing.productName());
        boolean ageChanged = newLower != current.ageLowerMonths() || newUpper != current.ageUpperMonths();
        boolean claimsChanged = !newClaims.equals(current.claims());
        boolean formulationChanged = !newFormulation.equals(current.formulation());
        boolean reportsChanged = !newReports.equals(current.safetyReports());
        boolean labelsChanged = !newLabels.equals(current.labels());
        boolean packagingChanged = !nullSafeEquals(newPackaging, current.packagingText());

        if (!nameChanged && !ageChanged && !claimsChanged && !formulationChanged
                && !reportsChanged && !labelsChanged && !packagingChanged) {
            throw new BusinessRejectionException("NO_CHANGE", "变更内容与当前生效修订完全一致，无需变更");
        }
        // 版本门禁：儿童属性、年龄或标识适用范围改变必须走实质变更
        boolean substantiveRequired =
                ageChanged || claimsChanged || formulationChanged || reportsChanged || labelsChanged;
        if (KIND_SIMPLE.equals(request.changeKind()) && substantiveRequired) {
            throw new BusinessRejectionException("CHILD_CHANGE_MUST_BE_SUBSTANTIVE",
                    "儿童属性、年龄或标识适用范围改变必须走实质变更，不能伪装为包装文字简易变更");
        }

        int newRevisionNo = filing.currentRevisionNo() + 1;
        if (KIND_SIMPLE.equals(request.changeKind())) {
            return simpleChange(filing, current, request, newName, newClaims, newFormulation,
                    newReports, newLabels, newPackaging, rule, newRevisionNo, submittedAt, now);
        }
        return substantiveChange(filing, request, newName, newLower, newUpper, newClaims,
                newFormulation, newReports, newLabels, newPackaging, rule, newRevisionNo,
                submittedAt, now);
    }

    private ChildFilingResultResponse simpleChange(ChildFilingRecord filing,
                                                   ChildRevisionRecord current,
                                                   ChildChangeRequest request,
                                                   String newName,
                                                   List<String> newClaims,
                                                   List<FormulationIngredient> newFormulation,
                                                   List<ChildSafetyReport> newReports,
                                                   List<ChildLabel> newLabels,
                                                   String newPackaging,
                                                   ChildAgeRule rule,
                                                   int newRevisionNo,
                                                   Instant submittedAt,
                                                   Instant now) {
        // 包装文字类简易变更：不重新审查，即时生效；变更生效与旧修订失效同事务。
        // 加锁顺序先生效修订行后聚合行，与生效执行器一致；失败随事务回滚。
        if (!childFilingPort.supersedeIfEffective(current.id(), now)) {
            throw new BusinessRejectionException("FILING_ALREADY_CHANGED",
                    "当前生效修订已被其他变更替代，本次简易变更失败（唯一生效修订）");
        }
        if (!childFilingPort.incrementVersionIfExpected(filing.filingNo(), request.expectedVersion(),
                newName, ChildFilingStatus.EFFECTIVE, newRevisionNo, now)) {
            throw new BusinessRejectionException("VERSION_MISMATCH",
                    "expectedVersion=" + request.expectedVersion() + " 与当前版本不一致，请重新读取后提交");
        }
        ChildRevisionRecord revision = new ChildRevisionRecord(null, filing.filingNo(), newRevisionNo,
                ChildRevisionType.SIMPLE, ChildRevisionStatus.EFFECTIVE,
                current.ageLowerMonths(), current.ageUpperMonths(), newClaims, newFormulation,
                newReports, newLabels, rule.ruleVersion(), labelDigest(newLabels), newPackaging,
                null, request.requestId(), submittedAt, null, null, now);
        childFilingPort.insertRevision(revision);

        List<ReviewDetail> details = List.of(new ReviewDetail(null, filing.filingNo(), newRevisionNo,
                ChildReviewEvaluator.STAGE_CHILD_REVIEW, "SIMPLE_CHANGE_NO_REVIEW", true,
                "仅包装文字类变更，走简易变更，不重新分龄审查与公示，即时生效", now));
        evidencePort.saveReviews(details);

        ChildFilingRecord savedFiling = childFilingPort.findFiling(filing.filingNo()).orElseThrow();
        ChildRevisionRecord savedRevision =
                childFilingPort.findRevision(filing.filingNo(), newRevisionNo).orElseThrow();
        ChildFilingResultResponse response = buildResult(savedFiling, savedRevision, details, false, now);
        idempotencyPort.complete(request.requestId(), 200, ChildFilingStatus.EFFECTIVE.name(),
                filing.filingNo(), JsonSupport.toJson(response), now);
        return response;
    }

    private ChildFilingResultResponse substantiveChange(ChildFilingRecord filing,
                                                        ChildChangeRequest request,
                                                        String newName,
                                                        int newLower,
                                                        int newUpper,
                                                        List<String> newClaims,
                                                        List<FormulationIngredient> newFormulation,
                                                        List<ChildSafetyReport> newReports,
                                                        List<ChildLabel> newLabels,
                                                        String newPackaging,
                                                        ChildAgeRule rule,
                                                        int newRevisionNo,
                                                        Instant submittedAt,
                                                        Instant now) {
        ChildReviewEvaluator.Result review = reviewEvaluator.evaluate(newLower, newUpper, newClaims,
                newFormulation, newReports, newLabels, rule, now, filing.filingNo(), newRevisionNo);

        boolean passed = review.passed();
        // 审查通过进入公示（旧生效修订在新修订生效前继续有效）；不通过零生效，聚合保持生效
        ChildFilingStatus newFilingStatus = passed
                ? ChildFilingStatus.IN_PUBLICITY : ChildFilingStatus.EFFECTIVE;
        ChildRevisionStatus revisionStatus = passed
                ? ChildRevisionStatus.PUBLIC : ChildRevisionStatus.REJECTED;
        Instant acceptedAt = passed ? submittedAt : null;
        Instant expiresAt = passed ? publicExpiry(submittedAt) : null;

        if (!childFilingPort.incrementVersionIfExpected(filing.filingNo(), request.expectedVersion(),
                newName, newFilingStatus, newRevisionNo, now)) {
            throw new BusinessRejectionException("VERSION_MISMATCH",
                    "expectedVersion=" + request.expectedVersion() + " 与当前版本不一致，请重新读取后提交");
        }

        ChildRevisionRecord revision = new ChildRevisionRecord(null, filing.filingNo(), newRevisionNo,
                ChildRevisionType.SUBSTANTIVE, revisionStatus, newLower, newUpper, newClaims,
                newFormulation, newReports, newLabels, rule.ruleVersion(), labelDigest(newLabels),
                newPackaging, passed ? null : String.join("；", review.reasons()), request.requestId(),
                submittedAt, acceptedAt, expiresAt, null);
        childFilingPort.insertRevision(revision);
        evidencePort.saveReviews(review.details());

        ChildFilingRecord savedFiling = childFilingPort.findFiling(filing.filingNo()).orElseThrow();
        ChildRevisionRecord savedRevision =
                childFilingPort.findRevision(filing.filingNo(), newRevisionNo).orElseThrow();
        ChildFilingResultResponse response =
                buildResult(savedFiling, savedRevision, review.details(), false, now);
        idempotencyPort.complete(request.requestId(), 200, newFilingStatus.name(),
                filing.filingNo(), JsonSupport.toJson(response), now);
        return response;
    }

    // ============================ 查询 ============================

    @Override
    @Transactional(readOnly = true)
    public ChildFilingResponse getFiling(String filingNo) {
        ChildFilingRecord filing = childFilingPort.findFiling(filingNo)
                .orElseThrow(() -> new NotFoundException("儿童备案不存在: " + filingNo));
        ChildRevisionRecord current = childFilingPort
                .findRevision(filingNo, filing.currentRevisionNo()).orElse(null);
        return ChildFilingResponse.from(filing, current);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChildRevisionResponse> getRevisions(String filingNo) {
        childFilingPort.findFiling(filingNo)
                .orElseThrow(() -> new NotFoundException("儿童备案不存在: " + filingNo));
        return childFilingPort.findRevisions(filingNo).stream()
                .map(ChildRevisionResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewDetailResponse> getEvidence(String filingNo, Integer revisionNo) {
        childFilingPort.findFiling(filingNo)
                .orElseThrow(() -> new NotFoundException("儿童备案不存在: " + filingNo));
        return evidencePort.findReviews(filingNo, revisionNo).stream()
                .map(ReviewDetailResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AgeRuleResponse getAgeRules() {
        ChildAgeRule rule = catalogPort.activeAgeRule();
        return new AgeRuleResponse(rule.ruleVersion(), rule.bands(),
                catalogPort.listRestrictedLimits(rule.ruleVersion()), catalogPort.listEfficacies());
    }

    @Override
    public int expireDuePublicities(Instant now) {
        Instant at = now == null ? now() : now;
        int effected = 0;
        for (ChildRevisionRecord revision : childFilingPort.findDuePublicRevisions(at)) {
            if (publicityEffector.effectuate(revision, at)) {
                effected++;
            }
        }
        return effected;
    }

    // ============================ 辅助方法 ============================

    private Optional<ChildFilingResultResponse> resolveIdempotency(String requestId,
                                                                   String operation,
                                                                   String fingerprint) {
        Optional<IdempotencyRow> existing = idempotencyPort.find(requestId);
        if (existing.isEmpty()) {
            try {
                idempotencyPort.claim(requestId, operation, fingerprint);
                return Optional.empty();
            } catch (DataIntegrityViolationException e) {
                // 并发同键：另一个事务刚刚插入主张行，落入下方重读判定
                existing = idempotencyPort.find(requestId);
                if (existing.isEmpty()) {
                    throw e;
                }
            }
        }
        IdempotencyRow row = existing.orElseThrow();
        if (!row.operation().equals(operation)) {
            throw new ConflictException("IDEMPOTENCY_KEY_CROSSED_OPERATION",
                    "requestId 已用于其它操作类型: " + row.operation());
        }
        if (!row.fingerprint().equals(fingerprint)) {
            throw new ConflictException("IDEMPOTENCY_PARAM_MISMATCH",
                    "同一 requestId 对应不同业务参数，拒绝处理（409）");
        }
        // 同键同参但首个事务尚未回填（并发重放）：等待首个事务完成后返回首次冻结结果
        if (row.httpStatus() == 0 || row.responseJson() == null || row.responseJson().isEmpty()) {
            row = awaitFirstCompletion(requestId);
        }
        ChildFilingResultResponse first =
                JsonSupport.fromJson(row.responseJson(), ChildFilingResultResponse.class);
        return Optional.of(new ChildFilingResultResponse(first.httpStatus(), first.filing(),
                true, first.evidence(), first.atUtc()));
    }

    /**
     * 并发重放时等待首个事务回填响应；带超时，避免首个事务异常时无限等待。
     */
    private IdempotencyRow awaitFirstCompletion(String requestId) {
        long deadline = System.nanoTime() + IDEMPOTENCY_WAIT_NANOS;
        IdempotencyRow row;
        do {
            try {
                Thread.sleep(IDEMPOTENCY_WAIT_STEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("IDEMPOTENCY_WAIT_INTERRUPTED",
                        "等待首次处理结果时被中断: " + requestId);
            }
            row = idempotencyPort.find(requestId).orElseThrow(() ->
                    new ConflictException("IDEMPOTENCY_RECORD_VANISHED", "幂等记录消失: " + requestId));
        } while ((row.httpStatus() == 0 || row.responseJson() == null || row.responseJson().isEmpty())
                && System.nanoTime() < deadline);

        if (row.httpStatus() == 0 || row.responseJson() == null || row.responseJson().isEmpty()) {
            throw new ConflictException("IDEMPOTENCY_FIRST_RESULT_TIMEOUT",
                    "等待首次处理结果超时: " + requestId);
        }
        return row;
    }

    /**
     * 参数校验（非法参数 400）：年龄闭区间 0≤下限≤上限≤144；档位编码须为当前规则档位；
     * 报告覆盖区间下限≤上限。
     */
    private void validateMaterials(ChildAgeRule rule,
                                   Integer ageLowerMonths,
                                   Integer ageUpperMonths,
                                   List<ChildSafetyReportRequest> safetyReports,
                                   List<ChildLabelRequest> labels) {
        if (ageLowerMonths == null || ageUpperMonths == null
                || ageLowerMonths < 0 || ageUpperMonths > 144 || ageLowerMonths > ageUpperMonths) {
            throw new IllegalArgumentException("年龄范围须为整数月闭区间且满足 0≤下限≤上限≤144: ["
                    + ageLowerMonths + ", " + ageUpperMonths + "]");
        }
        Set<String> bandCodes = rule.bands().stream().map(AgeBand::bandCode).collect(Collectors.toSet());
        for (ChildSafetyReportRequest r : safetyReports == null
                ? List.<ChildSafetyReportRequest>of() : safetyReports) {
            if (!bandCodes.contains(r.bandCode())) {
                throw new IllegalArgumentException("未知分龄档位编码: " + r.bandCode());
            }
            if (r.coverLowerMonths() == null || r.coverUpperMonths() == null
                    || r.coverLowerMonths() > r.coverUpperMonths()) {
                throw new IllegalArgumentException(
                        "安全报告覆盖年龄区间非法（下限≤上限且 0~144）: " + r.reportNo());
            }
        }
        for (ChildLabelRequest l : labels == null ? List.<ChildLabelRequest>of() : labels) {
            if (!bandCodes.contains(l.bandCode())) {
                throw new IllegalArgumentException("未知分龄档位编码: " + l.bandCode());
            }
        }
    }

    private List<FormulationIngredient> toFormulation(List<FormulationItemRequest> items) {
        return (items == null ? List.<FormulationItemRequest>of() : items).stream()
                .map(i -> new FormulationIngredient(i.code(), i.name(), i.concentrationPct()))
                .toList();
    }

    private List<ChildSafetyReport> registerReports(List<ChildSafetyReportRequest> requests) {
        List<ChildSafetyReport> reports = new java.util.ArrayList<>();
        for (ChildSafetyReportRequest r : requests == null
                ? List.<ChildSafetyReportRequest>of() : requests) {
            ChildSafetyReport report = new ChildSafetyReport(r.reportNo(), r.bandCode(),
                    r.coverLowerMonths(), r.coverUpperMonths(), r.conclusionText());
            safetyReportPort.upsert(report);
            reports.add(report);
        }
        return List.copyOf(reports);
    }

    private List<ChildLabel> toLabels(List<ChildLabelRequest> requests) {
        return (requests == null ? List.<ChildLabelRequest>of() : requests).stream()
                .map(l -> new ChildLabel(l.bandCode(), l.labelText()))
                .toList();
    }

    /** 标识文本摘要：结构化 JSON 的 SHA-256，合法文本含分隔符亦能与多个标识区分。 */
    private String labelDigest(List<ChildLabel> labels) {
        return FingerprintSupport.sha256(Map.of("labels", labels));
    }

    private String allocateFilingNo() {
        long seq = childFilingPort.nextFilingSequence();
        return String.format("SYN-C-%06d", seq);
    }

    /**
     * 公示期满口径：受理日 D（UTC）起 10 个自然日，恰第 10 日 24:00 期满，
     * 即 D+10 日 00:00:00 UTC（到期判定使用 &lt;=，恰期满当时即可生效）。
     */
    private Instant publicExpiry(Instant acceptedAtUtc) {
        LocalDate noticeDate = LocalDate.ofInstant(acceptedAtUtc, ZoneOffset.UTC);
        return noticeDate.plusDays(PUBLIC_NOTICE_DAYS).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private ChildFilingResultResponse buildResult(ChildFilingRecord filing,
                                                  ChildRevisionRecord revision,
                                                  List<ReviewDetail> details,
                                                  boolean replay,
                                                  Instant atUtc) {
        List<ReviewDetailResponse> evidence = details.stream()
                .map(ReviewDetailResponse::from).toList();
        return new ChildFilingResultResponse(200, ChildFilingResponse.from(filing, revision),
                replay, evidence, atUtc);
    }

    private static boolean nullSafeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
