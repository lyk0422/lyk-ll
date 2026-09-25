package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.domain.ChangeType;
import com.example.starter.cosmetic.domain.FilingRecord;
import com.example.starter.cosmetic.domain.FilingStatus;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.domain.ReviewDetail;
import com.example.starter.cosmetic.domain.TestReportRecord;
import com.example.starter.cosmetic.port.ActiveSlot;
import com.example.starter.cosmetic.port.FilingPort;
import com.example.starter.cosmetic.port.CatalogPort;
import com.example.starter.cosmetic.port.IdempotencyPort;
import com.example.starter.cosmetic.port.IdempotencyRow;
import com.example.starter.cosmetic.port.ReportPort;
import com.example.starter.cosmetic.support.FingerprintSupport;
import com.example.starter.cosmetic.support.JsonSupport;
import com.example.starter.cosmetic.web.dto.ChangeFilingRequest;
import com.example.starter.cosmetic.web.dto.FilingResponse;
import com.example.starter.cosmetic.web.dto.FilingResultResponse;
import com.example.starter.cosmetic.web.dto.FormulationItemRequest;
import com.example.starter.cosmetic.web.dto.SubmitFilingRequest;
import com.example.starter.cosmetic.web.dto.TestReportRequest;
import com.example.starter.cosmetic.web.dto.ReviewDetailResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 备案领域服务默认实现。
 *
 * <p>所有写操作以 requestId 幂等：指纹覆盖 requestId 与全部业务字段，
 * 同键同参返回首次结果（idempotentReplay=true），同键异参 409。</p>
 */
@Service
public class FilingServiceImpl implements FilingService {

    private static final String OP_SUBMIT = "SUBMIT";
    private static final String OP_CHANGE = "CHANGE";
    private static final String OP_WITHDRAW = "WITHDRAW";
    private static final int PUBLIC_NOTICE_DAYS = 10;
    private static final long IDEMPOTENCY_WAIT_NANOS = 10_000_000_000L;
    private static final long IDEMPOTENCY_WAIT_STEP_MS = 20L;

    private final FilingPort filingPort;
    private final CatalogPort catalogPort;
    private final ReportPort reportPort;
    private final IdempotencyPort idempotencyPort;
    private final PublicNoticeEffector publicNoticeEffector;
    private final Clock clock;
    private final FormReviewEvaluator formReviewEvaluator;

    public FilingServiceImpl(FilingPort filingPort,
                             CatalogPort catalogPort,
                             ReportPort reportPort,
                             IdempotencyPort idempotencyPort,
                             PublicNoticeEffector publicNoticeEffector,
                             Clock clock) {
        this.filingPort = filingPort;
        this.catalogPort = catalogPort;
        this.reportPort = reportPort;
        this.idempotencyPort = idempotencyPort;
        this.publicNoticeEffector = publicNoticeEffector;
        this.clock = clock;
        this.formReviewEvaluator = new FormReviewEvaluator(catalogPort);
    }

    private Instant now() {
        return clock.instant();
    }

    // ============================ 备案提交 ============================

    @Override
    @Transactional
    public FilingResultResponse submit(SubmitFilingRequest request) {
        Instant now = now();
        Map<String, Object> fingerprintFields = new LinkedHashMap<>();
        fingerprintFields.put("requestId", request.requestId());
        fingerprintFields.put("productCode", request.productCode());
        fingerprintFields.put("productName", request.productName());
        fingerprintFields.put("formulation", request.formulation());
        fingerprintFields.put("claims", request.claims());
        fingerprintFields.put("reports", request.reports() == null ? List.of() : request.reports());
        String fingerprint = FingerprintSupport.sha256(fingerprintFields);

        Optional<FilingResultResponse> replay =
                resolveIdempotency(request.requestId(), OP_SUBMIT, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        List<FormulationIngredient> formulation = request.formulation().stream()
                .map(i -> new FormulationIngredient(i.code(), i.name(), i.concentrationPct()))
                .toList();
        List<String> claims = List.copyOf(request.claims());
        List<TestReportRecord> reports = registerReports(request.reports());

        int versionNo = filingPort.nextProductVersion(request.productCode());
        String filingNo = allocateFilingNo();
        LocalDate submitDate = LocalDate.ofInstant(now, ZoneOffset.UTC);

        FormReviewEvaluator.Result review = formReviewEvaluator.evaluate(
                request.productName(), formulation, claims, reports, submitDate, now, filingNo, versionNo);

        List<ReviewDetail> details = new ArrayList<>(review.details());
        // 注销后新宣称审查一律拒绝（独立规则，可复算）
        boolean postWithdrawalBlocked = filingPort.hasWithdrawnRecord(request.productCode());
        if (postWithdrawalBlocked) {
            details.add(new ReviewDetail(null, filingNo, versionNo, FormReviewEvaluator.STAGE_FORM,
                    "POST_WITHDRAWAL_NEW_CLAIM_REJECTED", false,
                    "该产品存在已注销备案，注销后新宣称审查一律拒绝", now));
        }
        // 同一产品存在未终态（在途/有效）备案时，不得再次新提交
        boolean inflightBlocked = hasInFlightFiling(request.productCode());
        if (inflightBlocked) {
            details.add(new ReviewDetail(null, filingNo, versionNo, FormReviewEvaluator.STAGE_FORM,
                    "PRODUCT_ALREADY_IN_FLIGHT", false,
                    "同一产品已存在公示/异议审查/生效中的备案，应通过变更接口办理", now));
        }

        boolean passed = review.passed() && !postWithdrawalBlocked && !inflightBlocked;
        List<String> reasons = new ArrayList<>(review.reasons());
        if (postWithdrawalBlocked) {
            reasons.add("[POST_WITHDRAWAL_NEW_CLAIM_REJECTED] 注销后新宣称审查一律拒绝");
        }
        if (inflightBlocked) {
            reasons.add("[PRODUCT_ALREADY_IN_FLIGHT] 同一产品只允许一个在途/有效备案");
        }

        FilingStatus status = passed ? FilingStatus.PUBLIC : FilingStatus.FORM_REJECTED;
        LocalDate noticeDate = passed ? submitDate : null;
        Instant expiresAt = passed ? publicExpiry(noticeDate) : null;

        FilingRecord record = new FilingRecord(
                null, filingNo, request.productCode(), request.productName(), versionNo, null,
                ChangeType.INITIAL, status, formulation, claims, reports, null,
                request.requestId(), now, now, noticeDate, expiresAt, null,
                null, null, passed ? null : String.join("；", reasons));

        filingPort.saveReviews(details);
        filingPort.insert(record);

        FilingRecord saved = filingPort.findLatestByNo(filingNo).orElseThrow();
        FilingResultResponse response = buildResult(saved, details, false, now);
        idempotencyPort.complete(request.requestId(), 200, status.name(), filingNo,
                JsonSupport.toJson(response), now);
        return response;
    }

    // ============================ 备案变更 ============================

    @Override
    @Transactional
    public FilingResultResponse change(String productCode, ChangeFilingRequest request) {
        Instant now = now();
        Map<String, Object> fingerprintFields = new LinkedHashMap<>();
        fingerprintFields.put("requestId", request.requestId());
        fingerprintFields.put("productCode", productCode);
        fingerprintFields.put("productName", request.productName());
        fingerprintFields.put("formulation", request.formulation());
        fingerprintFields.put("claims", request.claims());
        fingerprintFields.put("reports", request.reports());
        fingerprintFields.put("packagingText", request.packagingText());
        String fingerprint = FingerprintSupport.sha256(fingerprintFields);

        Optional<FilingResultResponse> replay = resolveIdempotency(request.requestId(), OP_CHANGE, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        ActiveSlot slot = filingPort.findActiveSlot(productCode)
                .orElseThrow(() -> new ConflictException("NO_ACTIVE_FILING",
                        "产品当前无有效备案，无法变更（可能尚未生效或已注销）: " + productCode));
        FilingRecord current = filingPort.findVersion(slot.filingNo(), slot.versionNo())
                .orElseThrow(() -> new IllegalStateException("有效槽指向版本不存在"));
        if (current.status() != FilingStatus.EFFECTIVE) {
            throw new ConflictException("ACTIVE_FILING_NOT_EFFECTIVE",
                    "当前有效槽备案状态异常: " + current.status());
        }
        // 上一实质变更新版本仍在公示/异议审查时，不允许叠加变更，避免多个在途版本竞争同一有效槽
        boolean anotherInFlight = filingPort.findByProduct(productCode).stream()
                .anyMatch(r -> r.id() != current.id()
                        && (r.status() == FilingStatus.PUBLIC
                        || r.status() == FilingStatus.OBJECTION_REVIEW));
        if (anotherInFlight) {
            throw new ConflictException("PREVIOUS_CHANGE_IN_PUBLICITY",
                    "该产品已有变更版本处于公示/异议审查中，须待其形成终态后再变更");
        }

        String newName = request.productName() == null ? current.productName() : request.productName();
        List<FormulationIngredient> newFormulation = request.formulation() == null
                ? current.formulation()
                : request.formulation().stream()
                .map(i -> new FormulationIngredient(i.code(), i.name(), i.concentrationPct())).toList();
        List<String> newClaims = request.claims() == null ? current.claims() : List.copyOf(request.claims());
        List<TestReportRecord> newReports = request.reports() == null
                ? current.reports()
                : registerReports(request.reports());
        String newPackaging = request.packagingText() == null
                ? current.packagingText()
                : request.packagingText();

        boolean formulationChanged = !newFormulation.equals(current.formulation());
        boolean claimsChanged = !newClaims.equals(current.claims());
        boolean packagingChanged = !nullSafeEquals(newPackaging, current.packagingText());
        boolean nameChanged = !newName.equals(current.productName());

        if (!formulationChanged && !claimsChanged && !packagingChanged && !nameChanged) {
            throw new ConflictException("NO_CHANGE", "变更内容与当前有效备案完全一致，无需变更");
        }

        int versionNo = filingPort.nextProductVersion(productCode);

        if (formulationChanged || claimsChanged) {
            return substantiveChange(request.requestId(), current, productCode, newName, newFormulation,
                    newClaims, newReports, newPackaging, versionNo, now);
        }
        return simpleChange(request.requestId(), current, productCode, newName, newFormulation,
                newClaims, newReports, newPackaging, versionNo, now);
    }

    private FilingResultResponse substantiveChange(String requestId,
                                                   FilingRecord current,
                                                   String productCode,
                                                   String newName,
                                                   List<FormulationIngredient> newFormulation,
                                                   List<String> newClaims,
                                                   List<TestReportRecord> newReports,
                                                   String newPackaging,
                                                   int versionNo,
                                                   Instant now) {
        String newFilingNo = allocateFilingNo();
        LocalDate submitDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
        FormReviewEvaluator.Result review = formReviewEvaluator.evaluate(
                newName, newFormulation, newClaims, newReports, submitDate, now, newFilingNo, versionNo);

        boolean passed = review.passed();
        FilingStatus status = passed ? FilingStatus.PUBLIC : FilingStatus.FORM_REJECTED;
        LocalDate noticeDate = passed ? submitDate : null;
        Instant expiresAt = passed ? publicExpiry(noticeDate) : null;

        FilingRecord record = new FilingRecord(
                null, newFilingNo, productCode, newName, versionNo, current.filingNo(),
                ChangeType.SUBSTANTIVE, status, newFormulation, newClaims, newReports, newPackaging,
                requestId, now, now, noticeDate, expiresAt, null, null, null,
                passed ? null : String.join("；", review.reasons()));
        filingPort.saveReviews(review.details());
        filingPort.insert(record);

        // 实质变更审查通过进入公示；旧备案在新版本生效前继续有效，待期满同事务切换。
        FilingRecord saved = filingPort.findLatestByNo(newFilingNo).orElseThrow();
        FilingResultResponse response = buildResult(saved, review.details(), false, now);
        idempotencyPort.complete(requestId, 200, status.name(), newFilingNo,
                JsonSupport.toJson(response), now);
        return response;
    }

    private FilingResultResponse simpleChange(String requestId,
                                              FilingRecord current,
                                              String productCode,
                                              String newName,
                                              List<FormulationIngredient> newFormulation,
                                              List<String> newClaims,
                                              List<TestReportRecord> newReports,
                                              String newPackaging,
                                              int versionNo,
                                              Instant now) {
        // 包装文字类（含产品名称文字）简易变更：引用原备案编号，不重新公示，即时生效。
        FilingRecord record = new FilingRecord(
                null, current.filingNo(), productCode, newName, versionNo, current.filingNo(),
                ChangeType.SIMPLE, FilingStatus.EFFECTIVE, newFormulation, newClaims, newReports,
                newPackaging, requestId, now, now, null, null, null, now, null, null);
        filingPort.insert(record);

        boolean superseded = filingPort.supersedeEffective(current.id(), now);
        if (!superseded) {
            // 并发下旧有效版本已被另一变更替代：本事务回滚，返回 409
            throw new ConflictException("FILING_ALREADY_CHANGED",
                    "当前有效版本已被其他变更替代，本次简易变更失败（唯一有效备案）");
        }
        filingPort.replaceSlot(productCode, current.filingNo(), versionNo, now);

        List<ReviewDetail> details = List.of(new ReviewDetail(null, current.filingNo(), versionNo,
                FormReviewEvaluator.STAGE_FORM, "SIMPLE_CHANGE_NO_REVIEW", true,
                "仅包装文字类变更，走简易变更，引用原备案编号 " + current.filingNo()
                        + "，不重新形式审查与公示，即时生效", now));
        filingPort.saveReviews(details);

        FilingRecord saved = filingPort.findVersion(current.filingNo(), versionNo).orElseThrow();
        FilingResultResponse response = buildResult(saved, details, false, now);
        idempotencyPort.complete(requestId, 200, FilingStatus.EFFECTIVE.name(), current.filingNo(),
                JsonSupport.toJson(response), now);
        return response;
    }

    // ============================ 公示异议 ============================

    @Override
    @Transactional
    public FilingResultResponse acceptObjection(String filingNo, Instant atUtc, String reason) {
        Instant objectionAt = atUtc == null ? now() : atUtc;
        FilingRecord record = filingPort.findLatestByNo(filingNo)
                .orElseThrow(() -> new NotFoundException("备案不存在: " + filingNo));
        if (record.status() != FilingStatus.PUBLIC) {
            throw new ConflictException("NOT_IN_PUBLICITY",
                    "当前状态不受理公示异议: " + record.status()
                            + "（公示与异议竞争唯一终态，可能已期满生效）");
        }
        // 期满时刻之后到达的异议无效；恰期满时刻(==)允许与自动生效并发竞争，由原子更新判定唯一终态
        if (record.publicExpiresAtUtc() != null && objectionAt.isAfter(record.publicExpiresAtUtc())) {
            throw new ConflictException("PUBLICITY_ALREADY_EXPIRED",
                    "异议受理时刻(UTC)=" + objectionAt + " 晚于公示期满时刻(UTC)="
                            + record.publicExpiresAtUtc() + "，异议不再受理");
        }
        boolean accepted = filingPort.acceptObjectionIfPublic(record.id(), objectionAt);
        if (!accepted) {
            throw new ConflictException("PUBLICITY_ALREADY_EXPIRED",
                    "公示已期满生效，异议不再受理（唯一终态）");
        }
        ReviewDetail detail = new ReviewDetail(null, filingNo, record.versionNo(), "OBJECTION",
                "OBJECTION_ACCEPTED", true,
                "公示期内受理异议，受理时刻(UTC)=" + objectionAt + "，转入异议审查；原因=" + reason,
                objectionAt);
        filingPort.saveReviews(List.of(detail));

        FilingRecord updated = filingPort.findById(record.id()).orElseThrow();
        return buildResult(updated, List.of(detail), false, objectionAt);
    }

    @Override
    @Transactional
    public FilingResultResponse resolveObjection(String filingNo, boolean objectionUpheld, String conclusion) {
        Instant now = now();
        FilingRecord record = filingPort.findLatestByNo(filingNo)
                .orElseThrow(() -> new NotFoundException("备案不存在: " + filingNo));
        if (record.status() != FilingStatus.OBJECTION_REVIEW) {
            throw new ConflictException("NOT_IN_OBJECTION_REVIEW",
                    "仅异议审查中的备案可作出异议结论，当前状态: " + record.status());
        }
        ReviewDetail detail;
        FilingRecord updated;
        if (objectionUpheld) {
            filingPort.markRejectedAfterObjection(record.id(), now, conclusion);
            detail = new ReviewDetail(null, filingNo, record.versionNo(), "OBJECTION",
                    "OBJECTION_UPHELD", false,
                    "异议成立，备案终态驳回：" + conclusion, now);
        } else {
            filingPort.reopenPublic(record.id());
            detail = new ReviewDetail(null, filingNo, record.versionNo(), "OBJECTION",
                    "OBJECTION_NOT_UPHELD", true,
                    "异议不成立，恢复公示；公示期满仍按原起算日 "
                            + record.publicNoticeDateUtc() + "（UTC）计算，期满时刻 "
                            + record.publicExpiresAtUtc() + "（UTC）：" + conclusion, now);
        }
        filingPort.saveReviews(List.of(detail));
        updated = filingPort.findById(record.id()).orElseThrow();
        return buildResult(updated, List.of(detail), false, now);
    }

    // ============================ 注销 ============================

    @Override
    @Transactional
    public FilingResultResponse withdraw(String filingNo, String requestId, String reason) {
        Instant now = now();
        Map<String, Object> fpFields = new LinkedHashMap<>();
        fpFields.put("requestId", requestId);
        fpFields.put("filingNo", filingNo);
        fpFields.put("reason", reason);
        String fingerprint = FingerprintSupport.sha256(fpFields);

        Optional<FilingResultResponse> replay = resolveIdempotency(requestId, OP_WITHDRAW, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        FilingRecord record = filingPort.findLatestByNo(filingNo)
                .orElseThrow(() -> new NotFoundException("备案不存在: " + filingNo));

        // 注销为终态：重复注销返回幂等成功
        if (record.status() == FilingStatus.WITHDRAWN) {
            FilingResultResponse response = buildResult(record,
                    filingPort.findReviews(filingNo, record.versionNo()), true, now);
            idempotencyPort.complete(requestId, 200, FilingStatus.WITHDRAWN.name(), filingNo,
                    JsonSupport.toJson(response), now);
            return response;
        }
        if (record.status() != FilingStatus.EFFECTIVE) {
            throw new ConflictException("NOT_EFFECTIVE",
                    "仅生效备案可注销，当前状态: " + record.status());
        }

        filingPort.markWithdrawn(record.id(), now, reason);
        filingPort.releaseSlot(record.productCode());
        ReviewDetail detail = new ReviewDetail(null, filingNo, record.versionNo(), "FORM",
                "WITHDRAWN_TERMINAL", true,
                "备案注销终态，注销时刻(UTC)=" + now + "，原因=" + reason
                        + "；注销后该产品新宣称审查一律拒绝", now);
        filingPort.saveReviews(List.of(detail));

        FilingRecord updated = filingPort.findById(record.id()).orElseThrow();
        FilingResultResponse response = buildResult(updated, List.of(detail), false, now);
        idempotencyPort.complete(requestId, 200, FilingStatus.WITHDRAWN.name(), filingNo,
                JsonSupport.toJson(response), now);
        return response;
    }

    // ============================ 查询 ============================

    @Override
    @Transactional(readOnly = true)
    public FilingResponse getFiling(String filingNo) {
        return FilingResponse.from(filingPort.findLatestByNo(filingNo)
                .orElseThrow(() -> new NotFoundException("备案不存在: " + filingNo)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FilingResponse> getProductHistory(String productCode) {
        List<FilingRecord> records = filingPort.findByProduct(productCode);
        if (records.isEmpty()) {
            throw new NotFoundException("产品无备案历史: " + productCode);
        }
        return records.stream().map(FilingResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewDetailResponse> getReviews(String filingNo, Integer versionNo) {
        filingPort.findLatestByNo(filingNo)
                .orElseThrow(() -> new NotFoundException("备案不存在: " + filingNo));
        return filingPort.findReviews(filingNo, versionNo).stream()
                .map(ReviewDetailResponse::from).toList();
    }

    @Override
    public int expireDuePublicNotices(Instant now) {
        Instant at = now == null ? now() : now;
        int effected = 0;
        for (FilingRecord record : filingPort.findDuePublicFilings(at)) {
            if (publicNoticeEffector.effectuate(record, at)) {
                effected++;
            }
        }
        return effected;
    }

    // ============================ 辅助方法 ============================

    private Optional<FilingResultResponse> resolveIdempotency(String requestId,
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
        // 同键同参但首个事务尚未回填（并发重放）：等待首个事务完成后返回首次结果
        if (row.httpStatus() == 0 || row.responseJson() == null || row.responseJson().isEmpty()) {
            row = awaitFirstCompletion(requestId);
        }
        FilingResultResponse first = JsonSupport.fromJson(row.responseJson(), FilingResultResponse.class);
        return Optional.of(new FilingResultResponse(first.httpStatus(), first.filing(),
                true, first.reviewDetails(), first.atUtc()));
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

    private List<TestReportRecord> registerReports(List<TestReportRequest> requests) {
        List<TestReportRecord> records = new ArrayList<>();
        for (TestReportRequest r : requests == null ? List.<TestReportRequest>of() : requests) {
            TestReportRecord rec = new TestReportRecord(r.reportNo(), r.efficacyCode(), r.issuedDate(),
                    Boolean.TRUE.equals(r.conclusionSupported()), r.conclusionText());
            reportPort.upsert(rec);
            records.add(rec);
        }
        return List.copyOf(records);
    }

    private boolean hasInFlightFiling(String productCode) {
        return filingPort.findByProduct(productCode).stream()
                .anyMatch(r -> r.status() == FilingStatus.PUBLIC
                        || r.status() == FilingStatus.OBJECTION_REVIEW
                        || r.status() == FilingStatus.EFFECTIVE
                        || r.status() == FilingStatus.SUBMITTED);
    }

    private String allocateFilingNo() {
        long seq = filingPort.nextFilingSequence();
        return String.format("SYN-F-%06d", seq);
    }

    /**
     * 公示期满口径：起算日 D（UTC）起 10 个自然日，恰第 10 日 24:00 期满，
     * 即 D+10 日 00:00:00 UTC（到期判定使用 &lt;=，恰期满当时即可生效）。
     */
    private Instant publicExpiry(LocalDate noticeDateUtc) {
        return noticeDateUtc.plusDays(PUBLIC_NOTICE_DAYS)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private FilingResultResponse buildResult(FilingRecord record,
                                             List<ReviewDetail> details,
                                             boolean replay,
                                             Instant atUtc) {
        List<ReviewDetailResponse> detailResponses = details.stream()
                .map(ReviewDetailResponse::from).toList();
        return new FilingResultResponse(200, FilingResponse.from(record), replay,
                detailResponses, atUtc);
    }

    private static boolean nullSafeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
