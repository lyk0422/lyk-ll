package com.example.starter.cosmetic.child.service;

import com.example.starter.cosmetic.child.domain.AgeTier;
import com.example.starter.cosmetic.child.domain.ChildChangeType;
import com.example.starter.cosmetic.child.domain.ChildFilingRecord;
import com.example.starter.cosmetic.child.domain.ChildFilingStatus;
import com.example.starter.cosmetic.child.domain.ChildReviewDetail;
import com.example.starter.cosmetic.child.domain.ChildSafetyReport;
import com.example.starter.cosmetic.child.port.ChildActiveSlot;
import com.example.starter.cosmetic.child.port.ChildCatalogPort;
import com.example.starter.cosmetic.child.port.ChildFilingPort;
import com.example.starter.cosmetic.child.port.ChildIdempotencyPort;
import com.example.starter.cosmetic.child.port.ChildIdempotencyRow;
import com.example.starter.cosmetic.child.port.ChildSafetyReportPort;
import com.example.starter.cosmetic.child.web.dto.ChildChangeFilingRequest;
import com.example.starter.cosmetic.child.web.dto.ChildCorrectFilingRequest;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResponse;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResultResponse;
import com.example.starter.cosmetic.child.web.dto.ChildReviewDetailResponse;
import com.example.starter.cosmetic.child.web.dto.ChildRuleResponse;
import com.example.starter.cosmetic.child.web.dto.ChildSafetyReportRequest;
import com.example.starter.cosmetic.child.web.dto.ChildSubmitFilingRequest;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.service.ConflictException;
import com.example.starter.cosmetic.service.NotFoundException;
import com.example.starter.cosmetic.support.FingerprintSupport;
import com.example.starter.cosmetic.support.JsonSupport;
import com.example.starter.cosmetic.web.dto.FormulationItemRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 儿童分龄备案领域服务默认实现。
 *
 * <p>所有写操作以 requestId 幂等：结构化指纹覆盖 requestId、路径键、expectedVersion、
 * 全部标量、有序集合（配方/宣称/分档报告）、标识/包装文本与精确受理时刻；
 * 字符串经 JSON 转义，合法文本即使含分隔符也不会与其它参数碰撞。
 * 业务、版本与幂等结果在同一事务内提交，任一步失败整体回滚（失败不占键）。</p>
 */
@Service
public class ChildFilingServiceImpl implements ChildFilingService {

    private static final String OP_SUBMIT = "CHILD_SUBMIT";
    private static final String OP_CORRECT = "CHILD_CORRECT";
    private static final String OP_CHANGE = "CHILD_CHANGE";
    private static final int PUBLIC_NOTICE_DAYS = 10;
    private static final long IDEMPOTENCY_WAIT_NANOS = 10_000_000_000L;
    private static final long IDEMPOTENCY_WAIT_STEP_MS = 20L;

    private final ChildFilingPort filingPort;
    private final ChildCatalogPort catalogPort;
    private final ChildSafetyReportPort reportPort;
    private final ChildIdempotencyPort idempotencyPort;
    private final ChildPublicNoticeEffector publicNoticeEffector;
    private final Clock clock;
    private final ChildAgeReviewEvaluator evaluator;

    public ChildFilingServiceImpl(ChildFilingPort filingPort,
                                  ChildCatalogPort catalogPort,
                                  ChildSafetyReportPort reportPort,
                                  ChildIdempotencyPort idempotencyPort,
                                  ChildPublicNoticeEffector publicNoticeEffector,
                                  Clock clock) {
        this.filingPort = filingPort;
        this.catalogPort = catalogPort;
        this.reportPort = reportPort;
        this.idempotencyPort = idempotencyPort;
        this.publicNoticeEffector = publicNoticeEffector;
        this.clock = clock;
        this.evaluator = new ChildAgeReviewEvaluator(catalogPort);
    }

    private Instant now() {
        return clock.instant();
    }

    // ============================ 首次提交 ============================

    @Override
    @Transactional
    public ChildFilingResultResponse submit(ChildSubmitFilingRequest request) {
        Instant at = request.atUtc();
        validateAgeRange(request.ageFromMonths(), request.ageToMonths());

        Map<String, Object> fp = new LinkedHashMap<>();
        fp.put("requestId", request.requestId());
        fp.put("productCode", request.productCode());
        fp.put("productName", request.productName());
        fp.put("ageFromMonths", request.ageFromMonths());
        fp.put("ageToMonths", request.ageToMonths());
        fp.put("formulation", request.formulation());
        fp.put("claims", request.claims());
        fp.put("safetyReports", request.safetyReports());
        fp.put("ageLabelText", request.ageLabelText());
        fp.put("atUtc", at.toString());
        String fingerprint = FingerprintSupport.sha256(fp);

        Optional<ChildFilingResultResponse> replay =
                resolveIdempotency(request.requestId(), OP_SUBMIT, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        // 同一产品存在公示/生效中的儿童备案时，不得再次新提交，应走变更
        boolean inflight = filingPort.findByProduct(request.productCode()).stream()
                .anyMatch(r -> r.status() == ChildFilingStatus.PUBLIC
                        || r.status() == ChildFilingStatus.EFFECTIVE);
        if (inflight) {
            throw new ConflictException("CHILD_PRODUCT_IN_FLIGHT",
                    "同一产品已存在公示/生效中的儿童备案，应通过变更或补正接口办理: "
                            + request.productCode());
        }

        int versionNo = filingPort.nextProductVersion(request.productCode());
        String filingNo = allocateFilingNo();
        String ruleVersion = catalogPort.currentRuleVersion();

        List<FormulationIngredient> formulation = toFormulation(request.formulation());
        List<String> claims = List.copyOf(request.claims());
        List<ChildSafetyReport> reports = registerReports(request.safetyReports());

        ChildFilingResultResponse response = reviewAndPersist(
                filingNo, request.productCode(), null, ChildChangeType.INITIAL, versionNo,
                request.productName(), request.ageFromMonths(), request.ageToMonths(),
                formulation, claims, reports, request.ageLabelText(), null,
                request.requestId(), ruleVersion, at);
        return response;
    }

    // ============================ 材料补正 ============================

    @Override
    @Transactional
    public ChildFilingResultResponse correct(String filingNo, ChildCorrectFilingRequest request) {
        Instant at = request.atUtc();
        validateAgeRange(request.ageFromMonths(), request.ageToMonths());

        Map<String, Object> fp = new LinkedHashMap<>();
        fp.put("requestId", request.requestId());
        fp.put("filingNo", filingNo);
        fp.put("expectedVersion", request.expectedVersion());
        fp.put("productName", request.productName());
        fp.put("ageFromMonths", request.ageFromMonths());
        fp.put("ageToMonths", request.ageToMonths());
        fp.put("formulation", request.formulation());
        fp.put("claims", request.claims());
        fp.put("safetyReports", request.safetyReports());
        fp.put("ageLabelText", request.ageLabelText());
        fp.put("atUtc", at.toString());
        String fingerprint = FingerprintSupport.sha256(fp);

        Optional<ChildFilingResultResponse> replay = resolveIdempotency(request.requestId(), OP_CORRECT, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        ChildFilingRecord latest = filingPort.findLatestByNo(filingNo)
                .orElseThrow(() -> new NotFoundException("儿童备案不存在: " + filingNo));

        if (request.expectedVersion() != latest.versionNo()) {
            throw new ConflictException("CHILD_VERSION_MISMATCH",
                    "expectedVersion=" + request.expectedVersion()
                            + " 与最新修订版本号 " + latest.versionNo() + " 不一致（409）");
        }
        if (latest.status() != ChildFilingStatus.FORM_REJECTED
                && latest.status() != ChildFilingStatus.PUBLIC) {
            // 已生效/终态失效的修订不能再走材料补正：生效后变更走 change
            throw new ChildBusinessRejectionException("CHILD_CORRECTION_NOT_ALLOWED",
                    "当前修订状态 " + latest.status() + " 不允许材料补正；仅形式驳回或公示中的待审修订可补正");
        }

        // 原子失效旧待审修订：仅 FORM_REJECTED/PUBLIC 可置 VOID；与公示期满生效竞争唯一终态。
        // 0 行说明旧修订已被期满生效（或被其它补正）抢占，本事务回滚（幂等主张一并回滚，不占键）。
        boolean voided = filingPort.voidPendingRevision(latest.id(), at);
        if (!voided) {
            throw new ConflictException("CHILD_REVISION_ALREADY_DECIDED",
                    "旧待审修订已形成唯一终态（可能已期满生效），补正失败");
        }

        int newVersion = latest.versionNo() + 1;
        List<FormulationIngredient> formulation = toFormulation(request.formulation());
        List<String> claims = List.copyOf(request.claims());
        List<ChildSafetyReport> reports = registerReports(request.safetyReports());

        return reviewAndPersist(
                filingNo, latest.productCode(), filingNo, ChildChangeType.CORRECTION, newVersion,
                request.productName(), request.ageFromMonths(), request.ageToMonths(),
                formulation, claims, reports, request.ageLabelText(), latest.packagingText(),
                request.requestId(), latest.ageRuleVersion(), at);
    }

    // ============================ 变更 ============================

    @Override
    @Transactional
    public ChildFilingResultResponse change(String productCode, ChildChangeFilingRequest request) {
        Instant at = request.atUtc();
        if (request.ageFromMonths() != null || request.ageToMonths() != null) {
            Integer from = request.ageFromMonths();
            Integer to = request.ageToMonths();
            // 部分提供时按“与当前有效值合并”在下方校验；这里先对显式值做边界检查
            if (from != null && (from < 0 || from > 144)) {
                throw new IllegalArgumentException("ageFromMonths 必须在 0..144");
            }
            if (to != null && (to < 0 || to > 144)) {
                throw new IllegalArgumentException("ageToMonths 必须在 0..144");
            }
        }

        Map<String, Object> fp = new LinkedHashMap<>();
        fp.put("requestId", request.requestId());
        fp.put("productCode", productCode);
        fp.put("expectedVersion", request.expectedVersion());
        fp.put("productName", request.productName());
        fp.put("ageFromMonths", request.ageFromMonths());
        fp.put("ageToMonths", request.ageToMonths());
        fp.put("formulation", request.formulation());
        fp.put("claims", request.claims());
        fp.put("safetyReports", request.safetyReports());
        fp.put("ageLabelText", request.ageLabelText());
        fp.put("packagingText", request.packagingText());
        fp.put("atUtc", at.toString());
        String fingerprint = FingerprintSupport.sha256(fp);

        Optional<ChildFilingResultResponse> replay = resolveIdempotency(request.requestId(), OP_CHANGE, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        ChildActiveSlot slot = filingPort.findActiveSlot(productCode)
                .orElseThrow(() -> new ConflictException("CHILD_NO_ACTIVE_FILING",
                        "产品当前无有效儿童备案，无法变更: " + productCode));
        ChildFilingRecord current = filingPort.findVersion(slot.filingNo(), slot.versionNo())
                .orElseThrow(() -> new IllegalStateException("儿童有效槽指向版本不存在"));
        if (current.status() != ChildFilingStatus.EFFECTIVE) {
            throw new ConflictException("CHILD_ACTIVE_NOT_EFFECTIVE", "当前有效槽状态异常: " + current.status());
        }
        if (request.expectedVersion() != current.versionNo()) {
            throw new ConflictException("CHILD_VERSION_MISMATCH",
                    "expectedVersion=" + request.expectedVersion()
                            + " 与当前有效版本号 " + current.versionNo() + " 不一致（409）");
        }
        boolean anotherInFlight = filingPort.findByProduct(productCode).stream()
                .anyMatch(r -> r.status() == ChildFilingStatus.PUBLIC);
        if (anotherInFlight) {
            throw new ChildBusinessRejectionException("CHILD_PREVIOUS_CHANGE_IN_PUBLICITY",
                    "该产品已有变更修订处于公示中，须待其形成终态后再变更");
        }

        String newName = request.productName() == null ? current.productName() : request.productName();
        int newFrom = request.ageFromMonths() == null ? current.ageFromMonths() : request.ageFromMonths();
        int newTo = request.ageToMonths() == null ? current.ageToMonths() : request.ageToMonths();
        validateAgeRange(newFrom, newTo);

        List<FormulationIngredient> newFormulation = request.formulation() == null
                ? current.formulation()
                : toFormulation(request.formulation());
        List<String> newClaims = request.claims() == null ? current.claims() : List.copyOf(request.claims());
        List<ChildSafetyReport> newReports = request.safetyReports() == null
                ? current.safetyReports()
                : registerReports(request.safetyReports());
        String newLabel = request.ageLabelText() == null ? current.ageLabelText() : request.ageLabelText();
        String newPackaging = request.packagingText() == null
                ? current.packagingText()
                : request.packagingText();

        boolean ageChanged = newFrom != current.ageFromMonths() || newTo != current.ageToMonths();
        boolean labelChanged = !newLabel.equals(current.ageLabelText());
        boolean formulationChanged = !newFormulation.equals(current.formulation());
        boolean claimsChanged = !newClaims.equals(current.claims());
        boolean packagingChanged = !nullSafeEquals(newPackaging, current.packagingText());
        boolean nameChanged = !newName.equals(current.productName());

        boolean substantive = ageChanged || labelChanged || formulationChanged || claimsChanged;

        if (!substantive && !packagingChanged && !nameChanged) {
            throw new ConflictException("CHILD_NO_CHANGE", "变更内容与当前有效备案完全一致，无需变更");
        }

        int versionNo = filingPort.nextProductVersion(productCode);
        String ruleVersion = catalogPort.currentRuleVersion();

        if (substantive) {
            // 儿童属性、年龄或标识适用范围（含配方/宣称）改变：必须实质变更，重新分龄审查、公示、新编号
            String newFilingNo = allocateFilingNo();
            return reviewAndPersist(
                    newFilingNo, productCode, current.filingNo(), ChildChangeType.SUBSTANTIVE, versionNo,
                    newName, newFrom, newTo, newFormulation, newClaims, newReports, newLabel,
                    newPackaging, request.requestId(), ruleVersion, at);
        }

        // 仅包装文字/产品名称文字：简易变更，同号版本+1，即时生效，原子替代旧有效版本
        return simpleChange(current, productCode, newName, newFrom, newTo, newFormulation, newClaims,
                newReports, newLabel, newPackaging, request.requestId(), versionNo, at);
    }

    private ChildFilingResultResponse simpleChange(ChildFilingRecord current,
                                                   String productCode,
                                                   String newName,
                                                   int from,
                                                   int to,
                                                   List<FormulationIngredient> formulation,
                                                   List<String> claims,
                                                   List<ChildSafetyReport> reports,
                                                   String label,
                                                   String packaging,
                                                   String requestId,
                                                   int versionNo,
                                                   Instant at) {
        ChildFilingRecord record = new ChildFilingRecord(
                null, current.filingNo(), productCode, newName, versionNo, current.filingNo(),
                ChildChangeType.SIMPLE, ChildFilingStatus.EFFECTIVE, from, to, current.requiredTiers(),
                formulation, claims, reports, label, current.ageLabelDigest(), current.ageRuleVersion(),
                packaging, requestId, at, at, null, null, at, null, null);
        filingPort.insert(record);

        boolean superseded = filingPort.supersedeEffective(current.id(), at);
        if (!superseded) {
            throw new ConflictException("CHILD_FILING_ALREADY_CHANGED",
                    "当前有效版本已被其它变更替代，本次简易变更失败（唯一有效备案）");
        }
        filingPort.replaceSlot(productCode, current.filingNo(), versionNo, at);

        List<ChildReviewDetail> details = List.of(new ChildReviewDetail(null, current.filingNo(), versionNo,
                "CHILD_SIMPLE_CHANGE_NO_REVIEW", true,
                "仅包装文字/名称类变更，走简易变更，沿用备案编号 " + current.filingNo()
                        + "，不重新分龄审查与公示，即时生效", at));
        filingPort.saveReviews(details);

        ChildFilingRecord saved = filingPort.findVersion(current.filingNo(), versionNo).orElseThrow();
        ChildFilingResultResponse response = buildResult(saved, details, false, at);
        idempotencyPort.complete(requestId, 200, ChildFilingStatus.EFFECTIVE.name(), current.filingNo(),
                JsonSupport.toJson(response), at);
        return response;
    }

    // ============================ 公示期满 ============================

    @Override
    public int expireDuePublicNotices(Instant now) {
        Instant at = now == null ? now() : now;
        int effected = 0;
        for (ChildFilingRecord record : filingPort.findDuePublicFilings(at)) {
            if (publicNoticeEffector.effectuate(record, at)) {
                effected++;
            }
        }
        return effected;
    }

    // ============================ 查询 ============================

    @Override
    @Transactional(readOnly = true)
    public ChildFilingResponse getFiling(String filingNo) {
        return ChildFilingResponse.from(filingPort.findLatestByNo(filingNo)
                .orElseThrow(() -> new NotFoundException("儿童备案不存在: " + filingNo)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChildFilingResponse> getProductHistory(String productCode) {
        List<ChildFilingRecord> records = filingPort.findByProduct(productCode);
        if (records.isEmpty()) {
            throw new NotFoundException("产品无儿童备案历史: " + productCode);
        }
        return records.stream().map(ChildFilingResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChildReviewDetailResponse> getReviews(String filingNo, Integer versionNo) {
        filingPort.findLatestByNo(filingNo)
                .orElseThrow(() -> new NotFoundException("儿童备案不存在: " + filingNo));
        return filingPort.findReviews(filingNo, versionNo).stream()
                .map(ChildReviewDetailResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ChildRuleResponse getRule() {
        String version = catalogPort.currentRuleVersion();
        List<ChildRuleResponse.TierView> tierViews = catalogPort.tiers(version).stream()
                .map(ChildRuleResponse.TierView::from).toList();
        Map<String, Map<String, BigDecimal>> limits = new LinkedHashMap<>();
        for (AgeTier tier : catalogPort.tiers(version)) {
            Map<String, BigDecimal> perTier = new LinkedHashMap<>();
            for (var limit : catalogPort.listLimits(version)) {
                if (limit.tier() == tier) {
                    perTier.put(limit.ingredientCode(), limit.maxConcentrationPct());
                }
            }
            limits.put(tier.name(), perTier);
        }
        return new ChildRuleResponse(version, tierViews, limits);
    }

    // ============================ 内部辅助 ============================

    /**
     * 执行分龄形式审查并持久化一个修订（首次/补正/实质变更共用）。
     * 通过进入公示（第 10 日 24:00 UTC 期满），不通过 FORM_REJECTED，零生效。
     */
    private ChildFilingResultResponse reviewAndPersist(
            String filingNo, String productCode, String parentFilingNo, ChildChangeType changeType,
            int versionNo, String productName, int from, int to,
            List<FormulationIngredient> formulation, List<String> claims,
            List<ChildSafetyReport> reports, String label, String packaging,
            String requestId, String ruleVersion, Instant at) {

        LocalDate submitDate = LocalDate.ofInstant(at, ZoneOffset.UTC);
        ChildAgeReviewEvaluator.Result review = evaluator.evaluate(
                productName, from, to, formulation, claims, reports, label,
                ruleVersion, at, filingNo, versionNo);

        boolean passed = review.passed();
        ChildFilingStatus status = passed ? ChildFilingStatus.PUBLIC : ChildFilingStatus.FORM_REJECTED;
        LocalDate noticeDate = passed ? submitDate : null;
        Instant expiresAt = passed ? publicExpiry(noticeDate) : null;
        String digest = labelDigest(label, from, to, ruleVersion);

        ChildFilingRecord record = new ChildFilingRecord(
                null, filingNo, productCode, productName, versionNo, parentFilingNo,
                changeType, status, from, to, review.requiredTiers(),
                formulation, claims, reports, label, digest, ruleVersion, packaging,
                requestId, at, at, noticeDate, expiresAt, null, null,
                passed ? null : String.join("；", review.reasons()));

        filingPort.saveReviews(review.details());
        filingPort.insert(record);

        ChildFilingRecord saved = filingPort.findLatestByNo(filingNo).orElseThrow();
        ChildFilingResultResponse response = buildResult(saved, review.details(), false, at);
        idempotencyPort.complete(requestId, 200, status.name(), filingNo,
                JsonSupport.toJson(response), at);
        return response;
    }

    private Optional<ChildFilingResultResponse> resolveIdempotency(String requestId,
                                                                   String operation,
                                                                   String fingerprint) {
        Optional<ChildIdempotencyRow> existing = idempotencyPort.find(requestId);
        if (existing.isEmpty()) {
            try {
                idempotencyPort.claim(requestId, operation, fingerprint);
                return Optional.empty();
            } catch (DataIntegrityViolationException e) {
                existing = idempotencyPort.find(requestId);
                if (existing.isEmpty()) {
                    throw e;
                }
            }
        }
        ChildIdempotencyRow row = existing.orElseThrow();
        if (!row.operation().equals(operation)) {
            throw new ConflictException("CHILD_IDEMPOTENCY_KEY_CROSSED_OPERATION",
                    "requestId 已用于其它操作类型: " + row.operation());
        }
        if (!row.fingerprint().equals(fingerprint)) {
            throw new ConflictException("CHILD_IDEMPOTENCY_PARAM_MISMATCH",
                    "同一 requestId 对应不同业务参数（含版本/集合/精确时刻），拒绝处理（409）");
        }
        if (row.httpStatus() == 0 || row.responseJson() == null || row.responseJson().isEmpty()) {
            row = awaitFirstCompletion(requestId);
        }
        ChildFilingResultResponse first =
                JsonSupport.fromJson(row.responseJson(), ChildFilingResultResponse.class);
        return Optional.of(new ChildFilingResultResponse(first.httpStatus(), first.filing(),
                true, first.reviewDetails(), first.atUtc()));
    }

    private ChildIdempotencyRow awaitFirstCompletion(String requestId) {
        long deadline = System.nanoTime() + IDEMPOTENCY_WAIT_NANOS;
        ChildIdempotencyRow row;
        do {
            try {
                Thread.sleep(IDEMPOTENCY_WAIT_STEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("CHILD_IDEMPOTENCY_WAIT_INTERRUPTED",
                        "等待首次处理结果时被中断: " + requestId);
            }
            row = idempotencyPort.find(requestId).orElseThrow(() ->
                    new ConflictException("CHILD_IDEMPOTENCY_RECORD_VANISHED", "幂等记录消失: " + requestId));
        } while ((row.httpStatus() == 0 || row.responseJson() == null || row.responseJson().isEmpty())
                && System.nanoTime() < deadline);

        if (row.httpStatus() == 0 || row.responseJson() == null || row.responseJson().isEmpty()) {
            throw new ConflictException("CHILD_IDEMPOTENCY_FIRST_RESULT_TIMEOUT",
                    "等待首次处理结果超时: " + requestId);
        }
        return row;
    }

    private List<FormulationIngredient> toFormulation(List<FormulationItemRequest> items) {
        return items.stream()
                .map(i -> new FormulationIngredient(i.code(), i.name(), i.concentrationPct()))
                .toList();
    }

    private List<ChildSafetyReport> registerReports(List<ChildSafetyReportRequest> requests) {
        List<ChildSafetyReport> records = new ArrayList<>();
        for (ChildSafetyReportRequest r : requests == null ? List.<ChildSafetyReportRequest>of() : requests) {
            AgeTier tier;
            try {
                tier = AgeTier.valueOf(r.tierCode());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("未知分档编码: " + r.tierCode()
                        + "（应为 TIER_0_36 或 TIER_37_144）");
            }
            if (r.coversFromMonths() > r.coversToMonths()) {
                throw new IllegalArgumentException(
                        "安全报告 " + r.reportNo() + " 覆盖年龄下限不得大于上限");
            }
            ChildSafetyReport rec = new ChildSafetyReport(
                    r.reportNo(), tier, r.issuedDate(), r.coversFromMonths(), r.coversToMonths(),
                    Boolean.TRUE.equals(r.conclusionSupported()), r.conclusionText());
            reportPort.upsert(rec);
            records.add(rec);
        }
        return List.copyOf(records);
    }

    private void validateAgeRange(int from, int to) {
        if (from < 0 || to > 144 || from > to) {
            throw new IllegalArgumentException(
                    "儿童年龄区间非法，须满足 0≤下限≤上限≤144（整数月，闭区间），实际: [" + from + "," + to + "]");
        }
    }

    private String allocateFilingNo() {
        long seq = filingPort.nextFilingSequence();
        return String.format("SYN-CF-%06d", seq);
    }

    private Instant publicExpiry(LocalDate noticeDateUtc) {
        return noticeDateUtc.plusDays(PUBLIC_NOTICE_DAYS)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** 标识文本摘要：把标识文本与其适用年龄范围、规则版本结构化绑定后取 SHA-256。 */
    private String labelDigest(String label, int from, int to, String ruleVersion) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("ageLabelText", label);
        structured.put("ageFromMonths", from);
        structured.put("ageToMonths", to);
        structured.put("ageRuleVersion", ruleVersion);
        return FingerprintSupport.sha256(structured);
    }

    private ChildFilingResultResponse buildResult(ChildFilingRecord record,
                                                  List<ChildReviewDetail> details,
                                                  boolean replay,
                                                  Instant atUtc) {
        List<ChildReviewDetailResponse> responses = details.stream()
                .map(ChildReviewDetailResponse::from).toList();
        return new ChildFilingResultResponse(200, ChildFilingResponse.from(record), replay,
                responses, atUtc);
    }

    private static boolean nullSafeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
