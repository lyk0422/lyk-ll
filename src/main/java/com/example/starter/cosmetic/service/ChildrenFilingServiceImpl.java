package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.domain.AgeLabel;
import com.example.starter.cosmetic.domain.ChangeType;
import com.example.starter.cosmetic.domain.ChildrenFilingRecord;
import com.example.starter.cosmetic.domain.ChildrenFilingStatus;
import com.example.starter.cosmetic.domain.ChildrenSafetyReport;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.domain.ReviewDetail;
import com.example.starter.cosmetic.domain.TestReportRecord;
import com.example.starter.cosmetic.port.ActiveSlot;
import com.example.starter.cosmetic.port.ChildrenCatalogPort;
import com.example.starter.cosmetic.port.ChildrenFilingPort;
import com.example.starter.cosmetic.port.ChildrenReportPort;
import com.example.starter.cosmetic.port.FilingPort;
import com.example.starter.cosmetic.port.IdempotencyPort;
import com.example.starter.cosmetic.port.IdempotencyRow;
import com.example.starter.cosmetic.port.ReportPort;
import com.example.starter.cosmetic.support.FingerprintSupport;
import com.example.starter.cosmetic.support.JsonSupport;
import com.example.starter.cosmetic.web.dto.AgeLabelRequest;
import com.example.starter.cosmetic.web.dto.ChangeChildrenFilingRequest;
import com.example.starter.cosmetic.web.dto.ChildrenEvidenceResponse;
import com.example.starter.cosmetic.web.dto.ChildrenFilingResponse;
import com.example.starter.cosmetic.web.dto.ChildrenFilingResultResponse;
import com.example.starter.cosmetic.web.dto.ChildrenSafetyReportRequest;
import com.example.starter.cosmetic.web.dto.CorrectChildrenFilingRequest;
import com.example.starter.cosmetic.web.dto.FormulationItemRequest;
import com.example.starter.cosmetic.web.dto.ReviewDetailResponse;
import com.example.starter.cosmetic.web.dto.SubmitChildrenFilingRequest;
import com.example.starter.cosmetic.web.dto.TestReportRequest;
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
 * 儿童化妆品备案领域服务默认实现。
 *
 * <p>写操作以 requestId 幂等：指纹覆盖 requestId 与全部业务字段（结构化 JSON 规范化，
 * 合法文本含分隔符亦可区分），同键同参重放首次冻结结果，异参 409；
 * 业务拒绝（422）/参数非法（400）/不存在（404）均回滚事务，失败不占键；
 * 业务、版本与幂等结果同事务提交。</p>
 */
@Service
public class ChildrenFilingServiceImpl implements ChildrenFilingService {

    private static final String OP_SUBMIT = "CHILD_SUBMIT";
    private static final String OP_CORRECT = "CHILD_CORRECT";
    private static final String OP_CHANGE = "CHILD_CHANGE";
    private static final int PUBLIC_NOTICE_DAYS = 10;
    private static final long IDEMPOTENCY_WAIT_NANOS = 10_000_000_000L;
    private static final long IDEMPOTENCY_WAIT_STEP_MS = 20L;

    private final ChildrenFilingPort filingPort;
    private final ChildrenCatalogPort catalogPort;
    private final ChildrenReportPort reportPort;
    private final ReportPort adultReportPort;
    private final FilingPort reviewStore;
    private final IdempotencyPort idempotencyPort;
    private final ChildrenPublicNoticeEffector publicNoticeEffector;
    private final Clock clock;
    private final ChildrenReviewEvaluator evaluator;

    public ChildrenFilingServiceImpl(ChildrenFilingPort filingPort,
                                     ChildrenCatalogPort catalogPort,
                                     ChildrenReportPort reportPort,
                                     ReportPort adultReportPort,
                                     FilingPort reviewStore,
                                     IdempotencyPort idempotencyPort,
                                     ChildrenPublicNoticeEffector publicNoticeEffector,
                                     Clock clock) {
        this.filingPort = filingPort;
        this.catalogPort = catalogPort;
        this.reportPort = reportPort;
        this.adultReportPort = adultReportPort;
        this.reviewStore = reviewStore;
        this.idempotencyPort = idempotencyPort;
        this.publicNoticeEffector = publicNoticeEffector;
        this.clock = clock;
        this.evaluator = new ChildrenReviewEvaluator(catalogPort);
    }

    private Instant now() {
        return clock.instant();
    }

    // ============================ 儿童备案提交 ============================

    @Override
    @Transactional
    public ChildrenFilingResultResponse submit(SubmitChildrenFilingRequest request) {
        Instant now = now();
        Map<String, Object> fp = new LinkedHashMap<>();
        fp.put("requestId", request.requestId());
        fp.put("productCode", request.productCode());
        fp.put("productName", request.productName());
        fp.put("ageLowerMonths", request.ageLowerMonths());
        fp.put("ageUpperMonths", request.ageUpperMonths());
        fp.put("formulation", request.formulation());
        fp.put("claims", request.claims());
        fp.put("safetyReports", request.safetyReports() == null ? List.of() : request.safetyReports());
        fp.put("labels", request.labels() == null ? List.of() : request.labels());
        fp.put("adultReports", request.adultReports() == null ? List.of() : request.adultReports());
        String fingerprint = FingerprintSupport.sha256(fp);

        Optional<ChildrenFilingResultResponse> replay =
                resolveIdempotency(request.requestId(), OP_SUBMIT, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        validateAgeRange(request.ageLowerMonths(), request.ageUpperMonths());
        List<FormulationIngredient> formulation = toFormulation(request.formulation());
        List<String> claims = List.copyOf(request.claims());
        List<ChildrenSafetyReport> reports = registerSafetyReports(request.safetyReports());
        List<AgeLabel> labels = toLabels(request.labels());
        int adultReportCount = registerAdultReports(request.adultReports());
        validateBandCodes(reports, labels);

        // 同一产品已存在在途/有效儿童备案时，不得再次新提交
        boolean inFlight = filingPort.findByProduct(request.productCode()).stream()
                .anyMatch(r -> r.status() == ChildrenFilingStatus.PUBLIC
                        || r.status() == ChildrenFilingStatus.EFFECTIVE);
        if (inFlight) {
            throw new UnprocessableException("CHILD_PRODUCT_ALREADY_IN_FLIGHT",
                    "同一产品已存在公示中或生效的儿童备案，应通过补正/变更办理: " + request.productCode(),
                    List.of("同一产品只允许一个在途/有效儿童备案"));
        }

        String filingNo = allocateFilingNo();
        int versionNo = 1;
        int revisionNo = 1;
        ChildrenReviewEvaluator.Result review = evaluator.evaluate(
                request.productName(), request.ageLowerMonths(), request.ageUpperMonths(),
                formulation, claims, reports, labels, adultReportCount, now, filingNo, versionNo);
        if (!review.passed()) {
            // 任一缺项一次列全并零生效：事务回滚，幂等键不占用
            throw new UnprocessableException("CHILD_REVIEW_REJECTED",
                    "儿童分龄资料审查不通过，缺项一次列全：" + String.join("；", review.reasons()),
                    review.reasons());
        }

        ChildrenFilingRecord record = acceptedRecord(filingNo, request.productCode(),
                request.productName(), versionNo, revisionNo, null, ChangeType.INITIAL,
                request.ageLowerMonths(), request.ageUpperMonths(), formulation, claims,
                reports, labels, null, request.requestId(), now);
        reviewStore.saveReviews(review.details());
        filingPort.insert(record);

        ChildrenFilingRecord saved = filingPort.findByNoAndVersion(filingNo, versionNo).orElseThrow();
        ChildrenFilingResultResponse response = buildResult(saved, review.details(), false, now);
        idempotencyPort.complete(request.requestId(), 200, ChildrenFilingStatus.PUBLIC.name(),
                filingNo, JsonSupport.toJson(response), now);
        return response;
    }

    // ============================ 材料补正 ============================

    @Override
    @Transactional
    public ChildrenFilingResultResponse correct(String filingNo, CorrectChildrenFilingRequest request) {
        Instant now = now();
        Map<String, Object> fp = new LinkedHashMap<>();
        fp.put("requestId", request.requestId());
        fp.put("filingNo", filingNo);
        fp.put("expectedVersion", request.expectedVersion());
        fp.put("productName", request.productName());
        fp.put("formulation", request.formulation());
        fp.put("claims", request.claims());
        fp.put("ageLowerMonths", request.ageLowerMonths());
        fp.put("ageUpperMonths", request.ageUpperMonths());
        fp.put("safetyReports", request.safetyReports());
        fp.put("labels", request.labels());
        String fingerprint = FingerprintSupport.sha256(fp);

        Optional<ChildrenFilingResultResponse> replay =
                resolveIdempotency(request.requestId(), OP_CORRECT, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        ChildrenFilingRecord current = filingPort.findLatestByNo(filingNo)
                .orElseThrow(() -> new NotFoundException("儿童备案不存在: " + filingNo));
        if (current.status() != ChildrenFilingStatus.PUBLIC) {
            throw new UnprocessableException("CORRECTION_NOT_IN_PUBLICITY",
                    "仅公示中的最新修订可材料补正，当前状态: " + current.status()
                            + "（已生效备案请走变更；补正与公示期满竞争唯一）",
                    List.of("当前状态不受理材料补正: " + current.status()));
        }
        checkExpectedVersion(current.productCode(), request.expectedVersion());

        // 儿童属性（宣称）、申请年龄、标识适用范围的改变必须走实质变更，不得借补正夹带
        List<String> substantiveOnly = new ArrayList<>();
        if (request.claims() != null && !List.copyOf(request.claims()).equals(current.claims())) {
            substantiveOnly.add("儿童功效宣称改变必须走实质变更");
        }
        if (request.ageLowerMonths() != null && request.ageLowerMonths() != current.ageLowerMonths()) {
            substantiveOnly.add("申请年龄下限改变必须走实质变更");
        }
        if (request.ageUpperMonths() != null && request.ageUpperMonths() != current.ageUpperMonths()) {
            substantiveOnly.add("申请年龄上限改变必须走实质变更");
        }
        List<AgeLabel> newLabels = request.labels() == null ? current.labels() : toLabels(request.labels());
        if (!labelRanges(newLabels).equals(labelRanges(current.labels()))) {
            substantiveOnly.add("标识适用范围改变必须走实质变更");
        }
        if (!substantiveOnly.isEmpty()) {
            throw new UnprocessableException("SUBSTANTIVE_CHANGE_REQUIRED",
                    "补正仅允许更新材料（安全报告/标识文本/配方/产品名称）："
                            + String.join("；", substantiveOnly),
                    substantiveOnly);
        }

        String newName = request.productName() == null ? current.productName() : request.productName();
        List<FormulationIngredient> newFormulation = request.formulation() == null
                ? current.formulation() : toFormulation(request.formulation());
        List<ChildrenSafetyReport> newReports = request.safetyReports() == null
                ? current.safetyReports() : registerSafetyReports(request.safetyReports());
        validateBandCodes(newReports, newLabels);

        int newVersion = current.versionNo() + 1;
        int newRevision = current.revisionNo() + 1;
        ChildrenReviewEvaluator.Result review = evaluator.evaluate(
                newName, current.ageLowerMonths(), current.ageUpperMonths(),
                newFormulation, current.claims(), newReports, newLabels, 0, now, filingNo, newVersion);
        if (!review.passed()) {
            throw new UnprocessableException("CHILD_REVIEW_REJECTED",
                    "补正材料审查不通过，缺项一次列全并零生效：" + String.join("；", review.reasons()),
                    review.reasons());
        }

        // 原子使旧修订待审批准失效；与公示期满生效竞争，仅一个获胜
        boolean superseded = filingPort.supersedePublicRevision(current.id(), now);
        if (!superseded) {
            throw new ConflictException("CORRECTION_RACE_LOST",
                    "旧修订已不在公示中（可能已期满生效），本次补正失败（竞态唯一）");
        }
        ChildrenFilingRecord record = acceptedRecord(filingNo, current.productCode(), newName,
                newVersion, newRevision, current.parentFilingNo(), current.changeType(),
                current.ageLowerMonths(), current.ageUpperMonths(), newFormulation,
                current.claims(), newReports, newLabels, current.packagingText(),
                request.requestId(), now);
        reviewStore.saveReviews(review.details());
        filingPort.insert(record);

        ChildrenFilingRecord saved = filingPort.findByNoAndVersion(filingNo, newVersion).orElseThrow();
        ChildrenFilingResultResponse response = buildResult(saved, review.details(), false, now);
        idempotencyPort.complete(request.requestId(), 200, ChildrenFilingStatus.PUBLIC.name(),
                filingNo, JsonSupport.toJson(response), now);
        return response;
    }

    // ============================ 儿童备案变更 ============================

    @Override
    @Transactional
    public ChildrenFilingResultResponse change(String productCode, ChangeChildrenFilingRequest request) {
        Instant now = now();
        Map<String, Object> fp = new LinkedHashMap<>();
        fp.put("requestId", request.requestId());
        fp.put("productCode", productCode);
        fp.put("expectedVersion", request.expectedVersion());
        fp.put("productName", request.productName());
        fp.put("ageLowerMonths", request.ageLowerMonths());
        fp.put("ageUpperMonths", request.ageUpperMonths());
        fp.put("formulation", request.formulation());
        fp.put("claims", request.claims());
        fp.put("safetyReports", request.safetyReports());
        fp.put("labels", request.labels());
        fp.put("packagingText", request.packagingText());
        String fingerprint = FingerprintSupport.sha256(fp);

        Optional<ChildrenFilingResultResponse> replay =
                resolveIdempotency(request.requestId(), OP_CHANGE, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        List<ChildrenFilingRecord> history = filingPort.findByProduct(productCode);
        if (history.isEmpty()) {
            throw new NotFoundException("产品无儿童备案: " + productCode);
        }
        ActiveSlot slot = filingPort.findActiveSlot(productCode)
                .orElseThrow(() -> new UnprocessableException("NO_ACTIVE_FILING",
                        "产品当前无生效儿童备案，无法变更（可能仍在公示中）: " + productCode,
                        List.of("仅生效儿童备案可变更")));
        ChildrenFilingRecord current = filingPort.findByNoAndVersion(slot.filingNo(), slot.versionNo())
                .orElseThrow(() -> new IllegalStateException("有效槽指向版本不存在"));
        if (current.status() != ChildrenFilingStatus.EFFECTIVE) {
            throw new ConflictException("ACTIVE_FILING_NOT_EFFECTIVE",
                    "当前有效槽备案状态异常: " + current.status());
        }
        checkExpectedVersion(productCode, request.expectedVersion());
        boolean anotherInFlight = history.stream()
                .anyMatch(r -> r.status() == ChildrenFilingStatus.PUBLIC);
        if (anotherInFlight) {
            throw new UnprocessableException("PREVIOUS_CHANGE_IN_PUBLICITY",
                    "该产品已有修订/变更版本处于公示中，须待其形成终态后再变更",
                    List.of("存在公示中的在途版本"));
        }

        String newName = request.productName() == null ? current.productName() : request.productName();
        int newLower = request.ageLowerMonths() == null ? current.ageLowerMonths() : request.ageLowerMonths();
        int newUpper = request.ageUpperMonths() == null ? current.ageUpperMonths() : request.ageUpperMonths();
        List<FormulationIngredient> newFormulation = request.formulation() == null
                ? current.formulation() : toFormulation(request.formulation());
        List<String> newClaims = request.claims() == null ? current.claims() : List.copyOf(request.claims());
        List<ChildrenSafetyReport> newReports = request.safetyReports() == null
                ? current.safetyReports() : registerSafetyReports(request.safetyReports());
        List<AgeLabel> newLabels = request.labels() == null ? current.labels() : toLabels(request.labels());
        String newPackaging = request.packagingText() == null ? current.packagingText() : request.packagingText();

        boolean nameChanged = !newName.equals(current.productName());
        boolean ageChanged = newLower != current.ageLowerMonths() || newUpper != current.ageUpperMonths();
        boolean formulationChanged = !newFormulation.equals(current.formulation());
        boolean claimsChanged = !newClaims.equals(current.claims());
        boolean reportsChanged = !newReports.equals(current.safetyReports());
        boolean labelsChanged = !newLabels.equals(current.labels());
        boolean packagingChanged = !nullSafeEquals(newPackaging, current.packagingText());

        if (!nameChanged && !ageChanged && !formulationChanged && !claimsChanged
                && !reportsChanged && !labelsChanged && !packagingChanged) {
            throw new UnprocessableException("NO_CHANGE",
                    "变更内容与当前有效备案完全一致，无需变更", List.of("无任何字段变化"));
        }

        // 儿童属性（宣称）、申请年龄或标识适用范围改变一律实质变更，不能伪装为包装文字简易变更
        boolean substantive = ageChanged || claimsChanged || labelsChanged
                || formulationChanged || reportsChanged;
        int newVersion = current.versionNo() + 1;
        if (substantive) {
            return substantiveChange(request.requestId(), current, productCode, newName,
                    newLower, newUpper, newFormulation, newClaims, newReports, newLabels,
                    newPackaging, newVersion, now);
        }
        return simpleChange(request.requestId(), current, productCode, newName,
                newFormulation, newClaims, newReports, newLabels, newPackaging, newVersion, now);
    }

    private ChildrenFilingResultResponse substantiveChange(String requestId,
                                                           ChildrenFilingRecord current,
                                                           String productCode,
                                                           String newName,
                                                           int newLower,
                                                           int newUpper,
                                                           List<FormulationIngredient> newFormulation,
                                                           List<String> newClaims,
                                                           List<ChildrenSafetyReport> newReports,
                                                           List<AgeLabel> newLabels,
                                                           String newPackaging,
                                                           int newVersion,
                                                           Instant now) {
        validateAgeRange(newLower, newUpper);
        validateBandCodes(newReports, newLabels);

        String newFilingNo = allocateFilingNo();
        ChildrenReviewEvaluator.Result review = evaluator.evaluate(
                newName, newLower, newUpper, newFormulation, newClaims, newReports, newLabels,
                0, now, newFilingNo, newVersion);
        if (!review.passed()) {
            throw new UnprocessableException("CHILD_REVIEW_REJECTED",
                    "实质变更审查不通过，缺项一次列全并零生效：" + String.join("；", review.reasons()),
                    review.reasons());
        }

        ChildrenFilingRecord record = acceptedRecord(newFilingNo, productCode, newName,
                newVersion, 1, current.filingNo(), ChangeType.SUBSTANTIVE,
                newLower, newUpper, newFormulation, newClaims, newReports, newLabels,
                newPackaging, requestId, now);
        reviewStore.saveReviews(review.details());
        filingPort.insert(record);

        // 旧备案在新版本生效前继续有效，待公示期满同事务切换（变更生效与旧备案失效原子且唯一）
        ChildrenFilingRecord saved = filingPort.findByNoAndVersion(newFilingNo, newVersion).orElseThrow();
        ChildrenFilingResultResponse response = buildResult(saved, review.details(), false, now);
        idempotencyPort.complete(requestId, 200, ChildrenFilingStatus.PUBLIC.name(),
                newFilingNo, JsonSupport.toJson(response), now);
        return response;
    }

    private ChildrenFilingResultResponse simpleChange(String requestId,
                                                      ChildrenFilingRecord current,
                                                      String productCode,
                                                      String newName,
                                                      List<FormulationIngredient> newFormulation,
                                                      List<String> newClaims,
                                                      List<ChildrenSafetyReport> newReports,
                                                      List<AgeLabel> newLabels,
                                                      String newPackaging,
                                                      int newVersion,
                                                      Instant now) {
        // 仅包装文字/产品名称文字变化：简易变更，引用原备案编号，不重新公示，即时生效
        ChildrenFilingRecord record = new ChildrenFilingRecord(
                null, current.filingNo(), productCode, newName, newVersion, current.revisionNo(),
                current.filingNo(), ChangeType.SIMPLE, ChildrenFilingStatus.EFFECTIVE,
                current.ageLowerMonths(), current.ageUpperMonths(), newFormulation, newClaims,
                newReports, newLabels, current.labelDigest(), current.ruleVersion(),
                newPackaging, requestId, now, null, null, now);
        filingPort.insert(record);

        boolean superseded = filingPort.supersedeEffective(current.id(), now);
        if (!superseded) {
            throw new ConflictException("FILING_ALREADY_CHANGED",
                    "当前有效版本已被其他变更替代，本次简易变更失败（唯一有效备案）");
        }
        filingPort.replaceSlot(productCode, current.filingNo(), newVersion, now);

        List<ReviewDetail> details = List.of(new ReviewDetail(null, current.filingNo(), newVersion,
                ChildrenReviewEvaluator.STAGE_FORM, "SIMPLE_CHANGE_NO_REVIEW", true,
                "仅包装文字/名称文字变更，走简易变更，引用原备案编号 " + current.filingNo()
                        + "，不重新审查与公示，即时生效", now));
        reviewStore.saveReviews(details);

        ChildrenFilingRecord saved = filingPort
                .findByNoAndVersion(current.filingNo(), newVersion).orElseThrow();
        ChildrenFilingResultResponse response = buildResult(saved, details, false, now);
        idempotencyPort.complete(requestId, 200, ChildrenFilingStatus.EFFECTIVE.name(),
                current.filingNo(), JsonSupport.toJson(response), now);
        return response;
    }

    // ============================ 查询 ============================

    @Override
    @Transactional(readOnly = true)
    public ChildrenFilingResponse getFiling(String filingNo) {
        return ChildrenFilingResponse.from(filingPort.findLatestByNo(filingNo)
                .orElseThrow(() -> new NotFoundException("儿童备案不存在: " + filingNo)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChildrenFilingResponse> getProductHistory(String productCode) {
        List<ChildrenFilingRecord> records = filingPort.findByProduct(productCode);
        if (records.isEmpty()) {
            throw new NotFoundException("产品无儿童备案历史: " + productCode);
        }
        return records.stream().map(ChildrenFilingResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ChildrenEvidenceResponse getEvidence(String filingNo) {
        List<ChildrenFilingRecord> revisions = filingPort.findRevisions(filingNo);
        if (revisions.isEmpty()) {
            throw new NotFoundException("儿童备案不存在: " + filingNo);
        }
        List<ChildrenEvidenceResponse.RevisionEvidence> evidence = revisions.stream()
                .map(r -> new ChildrenEvidenceResponse.RevisionEvidence(
                        r.versionNo(), r.revisionNo(), r.status().name(), r.changeType().name(),
                        r.ruleVersion(), r.labelDigest(), r.safetyReports(), r.labels(),
                        reviewStore.findReviews(filingNo, r.versionNo()).stream()
                                .map(ReviewDetailResponse::from).toList()))
                .toList();
        return new ChildrenEvidenceResponse(filingNo, revisions.get(0).productCode(), evidence);
    }

    @Override
    public int expireDuePublicNotices(Instant now) {
        Instant at = now == null ? now() : now;
        int effected = 0;
        for (ChildrenFilingRecord record : filingPort.findDuePublicFilings(at)) {
            if (publicNoticeEffector.effectuate(record, at)) {
                effected++;
            }
        }
        return effected;
    }

    // ============================ 辅助方法 ============================

    /** 构造受理冻结的新备案/修订行：冻结报告清单、分龄规则版本及标识文本摘要。 */
    private ChildrenFilingRecord acceptedRecord(String filingNo,
                                                String productCode,
                                                String productName,
                                                int versionNo,
                                                int revisionNo,
                                                String parentFilingNo,
                                                ChangeType changeType,
                                                int ageLower,
                                                int ageUpper,
                                                List<FormulationIngredient> formulation,
                                                List<String> claims,
                                                List<ChildrenSafetyReport> reports,
                                                List<AgeLabel> labels,
                                                String packagingText,
                                                String requestId,
                                                Instant now) {
        LocalDate submitDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return new ChildrenFilingRecord(
                null, filingNo, productCode, productName, versionNo, revisionNo, parentFilingNo,
                changeType, ChildrenFilingStatus.PUBLIC, ageLower, ageUpper, formulation, claims,
                List.copyOf(reports), List.copyOf(labels), labelDigest(labels),
                catalogPort.currentRuleVersion(), packagingText, requestId, now,
                submitDate, publicExpiry(submitDate), null);
    }

    /** 标识文本摘要：结构化 JSON 规范化后的 SHA-256，合法文本含分隔符亦可区分。 */
    private String labelDigest(List<AgeLabel> labels) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("labels", labels);
        return FingerprintSupport.sha256(fields);
    }

    private void checkExpectedVersion(String productCode, int expectedVersion) {
        int current = filingPort.currentVersion(productCode);
        if (current != expectedVersion) {
            throw new ConflictException("VERSION_CONFLICT",
                    "expectedVersion=" + expectedVersion + " 与当前版本 " + current
                            + " 不一致（版本从1开始，成功修改加1）");
        }
    }

    private void validateAgeRange(int lower, int upper) {
        if (lower < 0 || upper > ChildrenReviewEvaluator.MAX_AGE_MONTHS || lower > upper) {
            throw new IllegalArgumentException(
                    "申请年龄范围非法：[" + lower + ", " + upper + "]（须满足 0≤下限≤上限≤"
                            + ChildrenReviewEvaluator.MAX_AGE_MONTHS + "，闭区间整数月）");
        }
    }

    private void validateBandCodes(List<ChildrenSafetyReport> reports, List<AgeLabel> labels) {
        for (ChildrenSafetyReport r : reports) {
            if (catalogPort.findBandRule(r.bandCode()).isEmpty()) {
                throw new IllegalArgumentException("未知年龄档编码（安全报告）: " + r.bandCode());
            }
            if (r.coverageLowerMonths() > r.coverageUpperMonths()) {
                throw new IllegalArgumentException(
                        "安全报告 " + r.reportNo() + " 覆盖年龄下限大于上限");
            }
        }
        for (AgeLabel l : labels) {
            if (catalogPort.findBandRule(l.bandCode()).isEmpty()) {
                throw new IllegalArgumentException("未知年龄档编码（适用年龄标识）: " + l.bandCode());
            }
            if (l.applicableLowerMonths() > l.applicableUpperMonths()) {
                throw new IllegalArgumentException("适用年龄标识适用范围下限大于上限: " + l.bandCode());
            }
        }
    }

    private List<FormulationIngredient> toFormulation(List<FormulationItemRequest> items) {
        return items == null ? List.of()
                : items.stream()
                .map(i -> new FormulationIngredient(i.code(), i.name(), i.concentrationPct()))
                .toList();
    }

    private List<ChildrenSafetyReport> registerSafetyReports(List<ChildrenSafetyReportRequest> requests) {
        List<ChildrenSafetyReport> records = new ArrayList<>();
        for (ChildrenSafetyReportRequest r : requests == null ? List.<ChildrenSafetyReportRequest>of() : requests) {
            ChildrenSafetyReport rec = new ChildrenSafetyReport(r.reportNo(), r.bandCode(),
                    r.coverageLowerMonths(), r.coverageUpperMonths(), r.issuedDate(),
                    Boolean.TRUE.equals(r.conclusionSupported()), r.conclusionText());
            reportPort.upsert(rec);
            records.add(rec);
        }
        return List.copyOf(records);
    }

    private int registerAdultReports(List<TestReportRequest> requests) {
        int count = 0;
        for (TestReportRequest r : requests == null ? List.<TestReportRequest>of() : requests) {
            adultReportPort.upsert(new TestReportRecord(r.reportNo(), r.efficacyCode(), r.issuedDate(),
                    Boolean.TRUE.equals(r.conclusionSupported()), r.conclusionText()));
            count++;
        }
        return count;
    }

    private List<AgeLabel> toLabels(List<AgeLabelRequest> requests) {
        return requests == null ? List.of()
                : requests.stream()
                .map(l -> new AgeLabel(l.bandCode(), l.labelText(),
                        l.applicableLowerMonths(), l.applicableUpperMonths()))
                .toList();
    }

    /** 标识适用范围集合（档编码+上下限），用于判定标识适用范围是否改变。 */
    private List<String> labelRanges(List<AgeLabel> labels) {
        return labels.stream()
                .map(l -> l.bandCode() + "[" + l.applicableLowerMonths() + "," + l.applicableUpperMonths() + "]")
                .sorted()
                .toList();
    }

    private String allocateFilingNo() {
        long seq = filingPort.nextFilingSequence();
        return String.format("SYN-C-%06d", seq);
    }

    /**
     * 公示期满口径：起算日 D（UTC）起 10 个自然日，恰第 10 日 24:00 期满，
     * 即 D+10 日 00:00:00 UTC（到期判定使用 &lt;=，恰期满当时即可生效）。
     */
    private Instant publicExpiry(LocalDate noticeDateUtc) {
        return noticeDateUtc.plusDays(PUBLIC_NOTICE_DAYS)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private ChildrenFilingResultResponse buildResult(ChildrenFilingRecord record,
                                                     List<ReviewDetail> details,
                                                     boolean replay,
                                                     Instant atUtc) {
        List<ReviewDetailResponse> detailResponses = details.stream()
                .map(ReviewDetailResponse::from).toList();
        return new ChildrenFilingResultResponse(200, ChildrenFilingResponse.from(record), replay,
                detailResponses, atUtc);
    }

    private Optional<ChildrenFilingResultResponse> resolveIdempotency(String requestId,
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
        ChildrenFilingResultResponse first =
                JsonSupport.fromJson(row.responseJson(), ChildrenFilingResultResponse.class);
        return Optional.of(new ChildrenFilingResultResponse(first.httpStatus(), first.filing(),
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

    private static boolean nullSafeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
