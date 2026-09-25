package com.example.starter.compliance.service;

import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.domain.DirectAdditive;
import com.example.starter.compliance.domain.FormulaContent;
import com.example.starter.compliance.domain.IngredientUse;
import com.example.starter.compliance.engine.LimitEngine;
import com.example.starter.compliance.engine.ReviewInput;
import com.example.starter.compliance.engine.ReviewModels.ReviewReport;
import com.example.starter.compliance.repo.AdditiveRow;
import com.example.starter.compliance.repo.ComplianceRepository;
import com.example.starter.compliance.repo.FilingRow;
import com.example.starter.compliance.repo.FormulaRow;
import com.example.starter.compliance.repo.IngredientAdditiveRow;
import com.example.starter.compliance.repo.LimitRow;
import com.example.starter.compliance.support.JsonSupport;
import com.example.starter.compliance.time.ComplianceClock;
import com.example.starter.compliance.web.Requests.FileFilingRequest;
import com.example.starter.compliance.web.Requests.FilingRefRequest;
import com.example.starter.compliance.web.Requests.FormulaRefRequest;
import com.example.starter.compliance.web.Requests.ProductionReportRequest;
import com.example.starter.compliance.web.Requests.StandardUpdateRequest;
import com.example.starter.compliance.web.Requests.SubmitFormulaRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 配方合规领域服务。
 *
 * <p>状态机：配方 SUBMITTED → REVIEWED（审查通过）→ APPROVED（批准并冻结版本快照）；
 * 批准新版本时在同一事务内暂停同产品旧有效备案，随后可对批准版本做量产备案（ACTIVE）。
 * 标准更新后受影响备案进入 REVIEW_WATCH，90 个 UTC 自然日内（恰第 90 日受理）复审，
 * 逾期自动暂停；注销为终态，注销后量产上报一律拒绝。
 *
 * <p>所有变更接口以 requestId 幂等：指纹覆盖 requestId 与全部业务字段，同键同参重放首次结果，
 * 同键异参返回 409。
 */
@Service
public class ComplianceService {

    /** 复审观察期限（UTC 自然日），截止日 = 观察起始日 + 90，恰第 90 日提交仍受理。 */
    public static final long REVIEW_GRACE_DAYS = 90L;

    private final ComplianceRepository repository;
    private final ComplianceClock clock;
    private final LimitEngine engine;
    private final FilingTransitions transitions;

    public ComplianceService(ComplianceRepository repository, ComplianceClock clock,
            LimitEngine engine, FilingTransitions transitions) {
        this.repository = repository;
        this.clock = clock;
        this.engine = engine;
        this.transitions = transitions;
    }

    // ---------------- 配方：提交 / 审查 / 批准 ----------------

    @Transactional
    public ServiceResult<Results.FormulaView> submitFormula(SubmitFormulaRequest request) {
        return withIdempotency(request.requestId(), request, Results.FormulaView.class, () -> {
            FormulaContent content = validateAndBuild(request);
            if (repository.findFormula(content.productCode(), content.version()).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "FORMULA_VERSION_EXISTS",
                        "同一产品版本已存在：" + content.productCode() + "#" + content.version());
            }
            long now = clock.nowMillis();
            long formulaId = repository.insertFormula(content, "SUBMITTED", now);
            for (DirectAdditive additive : content.additives()) {
                repository.insertDirectAdditive(formulaId, additive.additiveCode(),
                        additive.amount());
            }
            for (IngredientUse use : content.ingredients()) {
                repository.insertIngredientUse(formulaId, use.ingredientCode(), use.ratio());
            }
            FormulaRow row = repository.findFormulaById(formulaId).orElseThrow();
            return new Outcome<>(buildFormulaView(row, false), HttpStatus.OK.value());
        });
    }

    @Transactional
    public ServiceResult<Results.ReviewView> reviewFormula(FormulaRefRequest request) {
        return withIdempotency(request.requestId(), request, Results.ReviewView.class, () -> {
            FormulaRow formula = requireFormula(request.formulaId());
            if ("APPROVED".equals(formula.state())) {
                throw new ApiException(HttpStatus.CONFLICT, "FORMULA_FROZEN",
                        "配方批准后版本冻结，不能再次审查");
            }
            ReviewReport report = evaluateFormula(formula);
            // 整单判定：通过进入 REVIEWED；任一违例保持 SUBMITTED，不产生批准/备案等部分后续记录。
            repository.markReviewed(formula.id(), report.passed(), JsonSupport.write(report),
                    clock.nowMillis());
            FormulaRow refreshed = repository.findFormulaById(formula.id()).orElseThrow();
            return new Outcome<>(
                    new Results.ReviewView(refreshed.id(), refreshed.state(), report, false),
                    HttpStatus.OK.value());
        });
    }

    @Transactional
    public ServiceResult<Results.FormulaView> approveFormula(FormulaRefRequest request) {
        return withIdempotency(request.requestId(), request, Results.FormulaView.class, () -> {
            FormulaRow formula = requireFormula(request.formulaId());
            if ("APPROVED".equals(formula.state())) {
                return new Outcome<>(buildFormulaView(formula, false), HttpStatus.OK.value());
            }
            if (!"REVIEWED".equals(formula.state()) || !Boolean.TRUE.equals(formula.reviewPassed())) {
                throw new ApiException(HttpStatus.CONFLICT, "REVIEW_NOT_PASSED",
                        "配方尚未通过合规审查，不能批准");
            }
            ReviewReport report = JsonSupport.read(formula.reviewJson(), ReviewReport.class);
            FormulaContent content = loadContent(formula);
            Map<String, LimitRow> limits = loadCurrentLimits(formula.categoryCode());
            ApprovedSnapshot snapshot = new ApprovedSnapshot(content, limits, report,
                    clock.nowMillis());
            String snapshotJson = JsonSupport.write(snapshot);
            repository.markApproved(formula.id(), snapshotJson, clock.nowMillis());

            // 新版本批准与旧版本备案暂停同一事务完成，保证任何时刻不出现双有效版本。
            for (FilingRow old : repository.findEffectiveFilingsByProduct(formula.productCode())) {
                repository.markSuspended(old.id(), "SUPERSEDED", clock.nowMillis());
                repository.releaseProduct(formula.productCode());
            }

            FormulaRow refreshed = repository.findFormulaById(formula.id()).orElseThrow();
            return new Outcome<>(buildFormulaView(refreshed, false), HttpStatus.OK.value());
        });
    }

    // ---------------- 量产备案 / 注销 / 复审 ----------------

    @Transactional
    public ServiceResult<Results.FilingView> fileFiling(FileFilingRequest request) {
        return withIdempotency(request.requestId(), request, Results.FilingView.class, () -> {
            FormulaRow formula = requireFormula(request.formulaId());
            if (!"APPROVED".equals(formula.state())) {
                throw new ApiException(HttpStatus.CONFLICT, "FORMULA_NOT_APPROVED",
                        "仅批准版本可量产备案");
            }
            Optional<FilingRow> existing = repository.findFilingByFormulaId(formula.id());
            if (existing.isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "FILING_EXISTS",
                        "该批准版本已存在备案：filingId=" + existing.get().id());
            }
            List<FilingRow> effective =
                    repository.findEffectiveFilingsByProduct(formula.productCode());
            if (!effective.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "FILING_VERSION_CONFLICT",
                        "同一产品存在有效备案版本，禁止重复有效备案");
            }
            long now = clock.nowMillis();
            long filingId = repository.insertFiling(formula.id(), formula.productCode(),
                    formula.version(), "ACTIVE", request.requestId(),
                    formula.approvedSnapshot(), now);
            try {
                repository.tryOccupyProduct(formula.productCode(), filingId);
            } catch (DuplicateKeyException e) {
                // 并发备案：唯一有效位冲突，整事务回滚，仅一方成功。
                throw new ApiException(HttpStatus.CONFLICT, "FILING_VERSION_CONFLICT",
                        "并发备案冲突，同一产品仅允许一个有效备案版本");
            }
            FilingRow row = repository.findFilingById(filingId).orElseThrow();
            return new Outcome<>(buildFilingView(row, false), HttpStatus.OK.value());
        });
    }

    @Transactional
    public ServiceResult<Results.FilingView> cancelFiling(FilingRefRequest request) {
        return withIdempotency(request.requestId(), request, Results.FilingView.class, () -> {
            FilingRow filing = requireFiling(request.filingId());
            // 注销为终态：重复注销返回幂等成功。
            if (!"CANCELLED".equals(filing.status())) {
                repository.markCancelled(filing.id(), clock.nowMillis());
                repository.releaseProduct(filing.productCode());
            }
            FilingRow refreshed = repository.findFilingById(filing.id()).orElseThrow();
            return new Outcome<>(buildFilingView(refreshed, false), HttpStatus.OK.value());
        });
    }

    @Transactional
    public ServiceResult<Results.FilingReviewView> reviewFiling(FilingRefRequest request) {
        return withIdempotency(request.requestId(), request, Results.FilingReviewView.class, () -> {
            FilingRow filing = requireFiling(request.filingId());
            long today = clock.todayEpochDay();
            if ("CANCELLED".equals(filing.status())) {
                throw new ApiException(HttpStatus.GONE, "FILING_CANCELLED",
                        "备案已注销，不能复审");
            }
            if (filing.watchStartDay() == null || !"REVIEW_WATCH".equals(filing.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "FILING_NOT_IN_WATCH",
                        "备案未进入复审观察，无需复审");
            }
            long deadline = filing.watchStartDay() + REVIEW_GRACE_DAYS;
            if (today > deadline) {
                // 逾期未复审：独立事务立即暂停并释放有效位；首次结果记 409 并落幂等记录，
                // 同一 requestId 重放返回相同 409 结果。
                transitions.suspendOverdueNewTransaction(filing);
                FilingRow suspended = repository.findFilingById(filing.id()).orElseThrow();
                return new Outcome<>(new Results.FilingReviewView(suspended.id(),
                        suspended.status(), false, null, clock.nowMillis(), false),
                        HttpStatus.CONFLICT.value());
            }

            // 按批准版本冻结快照复算，但限量取当前最新标准。
            ApprovedSnapshot snapshot =
                    JsonSupport.read(filing.snapshot(), ApprovedSnapshot.class);
            ReviewInput input = buildInput(snapshot.content(),
                    loadCurrentLimits(snapshot.content().categoryCode()));
            ReviewReport report = engine.evaluate(input, clock.nowMillis());
            boolean passed = report.passed();
            if (passed) {
                repository.markActive(filing.id());
            } else {
                repository.markSuspended(filing.id(), "REVIEW_FAILED", clock.nowMillis());
                repository.releaseProduct(filing.productCode());
            }
            repository.insertFilingReview(filing.id(), request.requestId(), passed,
                    JsonSupport.write(report), clock.nowMillis());
            FilingRow refreshed = repository.findFilingById(filing.id()).orElseThrow();
            return new Outcome<>(new Results.FilingReviewView(refreshed.id(),
                    refreshed.status(), passed, report, clock.nowMillis(), false),
                    HttpStatus.OK.value());
        });
    }

    @Transactional
    public ServiceResult<Results.ProductionView> reportProduction(ProductionReportRequest request) {
        return withIdempotency(request.requestId(), request, Results.ProductionView.class, () -> {
            if (request.batchNo() == null || request.batchNo().isBlank()) {
                throw invalid("批次号不能为空");
            }
            if (request.quantityKg() == null || request.quantityKg().signum() < 0) {
                throw invalid("量产数量必须为非负数");
            }
            FilingRow filing = requireFiling(request.filingId());
            long today = clock.todayEpochDay();
            if ("CANCELLED".equals(filing.status())) {
                throw new ApiException(HttpStatus.GONE, "FILING_CANCELLED",
                        "备案已注销，量产记录上报一律拒绝");
            }
            if ("SUSPENDED".equals(filing.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "FILING_SUSPENDED",
                        "备案已暂停，量产记录上报拒绝");
            }
            if ("REVIEW_WATCH".equals(filing.status())
                    && today > filing.watchStartDay() + REVIEW_GRACE_DAYS) {
                // 逾期未复审：独立事务立即暂停备案；量产记录不写入，首次结果记 409 并落幂等记录。
                transitions.suspendOverdueNewTransaction(filing);
                FilingRow suspended = repository.findFilingById(filing.id()).orElseThrow();
                return new Outcome<>(new Results.ProductionView(suspended.id(), request.batchNo(),
                        null, clock.nowMillis(), false), HttpStatus.CONFLICT.value());
            }
            long now = clock.nowMillis();
            repository.insertProductionRecord(filing.id(), request.batchNo(),
                    request.quantityKg(), now);
            return new Outcome<>(new Results.ProductionView(filing.id(), request.batchNo(),
                    request.quantityKg().stripTrailingZeros().toPlainString(), now, false),
                    HttpStatus.OK.value());
        });
    }

    // ---------------- 标准更新 / 逾期巡检 / 查询 ----------------

    @Transactional
    public ServiceResult<Results.StandardView> updateStandard(StandardUpdateRequest request) {
        return withIdempotency(request.requestId(), request, Results.StandardView.class, () -> {
            if (request.additiveCode() == null || request.additiveCode().isBlank()) {
                throw invalid("添加剂编码不能为空");
            }
            if (request.categoryCode() == null || request.categoryCode().isBlank()) {
                throw invalid("食品分类编码不能为空");
            }
            if (request.maxAmount() == null || request.maxAmount().signum() <= 0) {
                throw invalid("最大使用量必须为正数");
            }
            if (repository.findAdditive(request.additiveCode()).isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ADDITIVE_NOT_FOUND",
                        "添加剂目录中不存在：" + request.additiveCode());
            }
            if (!repository.categoryExists(request.categoryCode())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CATEGORY_NOT_FOUND",
                        "食品分类不存在：" + request.categoryCode());
            }
            long now = clock.nowMillis();
            Optional<LimitRow> current =
                    repository.findLimit(request.additiveCode(), request.categoryCode());
            if (current.isPresent()) {
                repository.updateLimit(request.additiveCode(), request.categoryCode(),
                        request.maxAmount(), now);
            } else {
                repository.insertLimit(request.additiveCode(), request.categoryCode(),
                        request.maxAmount(), now);
            }

            // 标准更新后，受影响的旧版本已备案配方进入复审观察（保留最早起始日，不延长 90 日期限）。
            List<Long> watchIds = new ArrayList<>();
            for (FilingRow filing : repository.findFilingsAffectedByStandard(
                    request.categoryCode(), request.additiveCode())) {
                repository.markReviewWatch(filing.id(), clock.todayEpochDay());
                watchIds.add(filing.id());
            }
            return new Outcome<>(new Results.StandardView(request.additiveCode(),
                    request.categoryCode(),
                    request.maxAmount().stripTrailingZeros().toPlainString(), now,
                    List.copyOf(watchIds), false), HttpStatus.OK.value());
        });
    }

    /** 逾期巡检：复审观察超过第 90 个 UTC 自然日仍未复审的备案自动暂停并释放有效位。 */
    @Transactional
    public Results.SweepView sweepOverdue() {
        long today = clock.todayEpochDay();
        List<Long> suspended = new ArrayList<>();
        for (FilingRow filing : repository.findReviewWatchFilings()) {
            if (today > filing.watchStartDay() + REVIEW_GRACE_DAYS) {
                suspendOverdue(filing);
                suspended.add(filing.id());
            }
        }
        return new Results.SweepView(today, List.copyOf(suspended));
    }

    @Transactional(readOnly = true)
    public Results.FormulaView getFormula(long formulaId) {
        return buildFormulaView(requireFormula(formulaId), false);
    }

    @Transactional(readOnly = true)
    public Results.FilingView getFiling(long filingId) {
        return buildFilingView(requireFiling(filingId), false);
    }

    @Transactional(readOnly = true)
    public Results.TrajectoryView getTrajectory(String productCode) {
        List<Results.FormulaView> formulas =
                repository.findFormulaVersionsByProduct(productCode).stream()
                        .map(row -> buildFormulaView(row, false))
                        .toList();
        List<Results.FilingView> filings = repository.findFilingsByProduct(productCode).stream()
                .map(row -> buildFilingView(row, false))
                .toList();
        return new Results.TrajectoryView(productCode, formulas, filings);
    }

    // ---------------- 内部辅助 ----------------

    private void suspendOverdue(FilingRow filing) {
        repository.markSuspended(filing.id(), "OVERDUE", clock.nowMillis());
        repository.releaseProduct(filing.productCode());
    }

    private FormulaRow requireFormula(Long formulaId) {
        if (formulaId == null) {
            throw invalid("formulaId 不能为空");
        }
        return repository.findFormulaById(formulaId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FORMULA_NOT_FOUND",
                        "配方不存在：" + formulaId));
    }

    private FilingRow requireFiling(Long filingId) {
        if (filingId == null) {
            throw invalid("filingId 不能为空");
        }
        return repository.findFilingById(filingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FILING_NOT_FOUND",
                        "备案不存在：" + filingId));
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM", message);
    }

    private FormulaContent validateAndBuild(SubmitFormulaRequest request) {
        if (request.productCode() == null || request.productCode().isBlank()) {
            throw invalid("产品编码不能为空");
        }
        if (request.productName() == null || request.productName().isBlank()) {
            throw invalid("产品名称不能为空");
        }
        if (request.categoryCode() == null || request.categoryCode().isBlank()) {
            throw invalid("食品分类编码不能为空");
        }
        if (!repository.categoryExists(request.categoryCode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CATEGORY_NOT_FOUND",
                    "食品分类不存在：" + request.categoryCode());
        }
        if (request.version() == null || request.version() < 1) {
            throw invalid("版本号必须为正整数");
        }

        List<DirectAdditive> additives = new ArrayList<>();
        for (var dto : nullToEmpty(request.additives())) {
            if (dto.additiveCode() == null || dto.additiveCode().isBlank()) {
                throw invalid("添加剂编码不能为空");
            }
            if (repository.findAdditive(dto.additiveCode()).isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ADDITIVE_NOT_FOUND",
                        "添加剂目录中不存在：" + dto.additiveCode());
            }
            if (dto.amount() == null || dto.amount().signum() < 0) {
                throw invalid("添加剂用量必须为非负数：" + dto.additiveCode());
            }
            additives.add(new DirectAdditive(dto.additiveCode(), dto.amount()));
        }

        List<IngredientUse> ingredients = new ArrayList<>();
        for (var dto : nullToEmpty(request.ingredients())) {
            if (dto.ingredientCode() == null || dto.ingredientCode().isBlank()) {
                throw invalid("复合配料编码不能为空");
            }
            if (!repository.ingredientExists(dto.ingredientCode())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INGREDIENT_NOT_FOUND",
                        "复合配料目录中不存在：" + dto.ingredientCode());
            }
            if (dto.ratio() == null || dto.ratio().signum() < 0
                    || dto.ratio().compareTo(BigDecimal.ONE) > 0) {
                throw invalid("配料占比必须在 0~1 之间：" + dto.ingredientCode());
            }
            ingredients.add(new IngredientUse(dto.ingredientCode(), dto.ratio()));
        }

        return new FormulaContent(request.productCode(), request.productName(),
                request.categoryCode(), request.version(), List.copyOf(additives),
                List.copyOf(ingredients));
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private FormulaContent loadContent(FormulaRow formula) {
        return new FormulaContent(formula.productCode(), formula.productName(),
                formula.categoryCode(), formula.version(),
                repository.findDirectAdditives(formula.id()),
                repository.findIngredientUses(formula.id()));
    }

    private Map<String, LimitRow> loadCurrentLimits(String categoryCode) {
        Map<String, LimitRow> limits = new LinkedHashMap<>();
        for (LimitRow row : repository.findLimitsByCategory(categoryCode)) {
            limits.put(row.additiveCode(), row);
        }
        return limits;
    }

    private ReviewReport evaluateFormula(FormulaRow formula) {
        FormulaContent content = loadContent(formula);
        return engine.evaluate(buildInput(content, loadCurrentLimits(content.categoryCode())),
                clock.nowMillis());
    }

    private ReviewInput buildInput(FormulaContent content, Map<String, LimitRow> limits) {
        Map<String, AdditiveRow> catalog = new TreeMap<>();
        for (DirectAdditive additive : content.additives()) {
            AdditiveRow row = repository.findAdditive(additive.additiveCode())
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                            "ADDITIVE_NOT_FOUND", "添加剂目录中不存在：" + additive.additiveCode()));
            catalog.put(row.code(), row);
        }

        List<String> ingredientCodes = content.ingredients().stream()
                .map(IngredientUse::ingredientCode)
                .distinct()
                .toList();
        List<IngredientAdditiveRow> carriedRows =
                repository.findIngredientAdditives(ingredientCodes);
        Map<String, List<IngredientAdditiveRow>> byIngredient = carriedRows.stream()
                .collect(Collectors.groupingBy(IngredientAdditiveRow::ingredientCode,
                        LinkedHashMap::new, Collectors.toList()));
        for (IngredientAdditiveRow row : carriedRows) {
            repository.findAdditive(row.additiveCode()).ifPresent(additive ->
                    catalog.putIfAbsent(additive.code(), additive));
        }

        Set<String> knownIngredients = Set.copyOf(ingredientCodes);
        return new ReviewInput(content.categoryCode(), content.additives(),
                content.ingredients(), catalog, knownIngredients, byIngredient, limits);
    }

    private Results.FormulaView buildFormulaView(FormulaRow row, boolean replayed) {
        ReviewReport report = row.reviewJson() == null ? null
                : JsonSupport.read(row.reviewJson(), ReviewReport.class);
        return new Results.FormulaView(row.id(), row.productCode(), row.productName(),
                row.categoryCode(), row.version(), row.state(), row.reviewPassed(), report,
                row.reviewedAt(), row.approvedAt(), replayed);
    }

    private Results.FilingView buildFilingView(FilingRow row, boolean replayed) {
        Long deadline = row.watchStartDay() == null ? null
                : row.watchStartDay() + REVIEW_GRACE_DAYS;
        Long daysRemaining = deadline == null ? null
                : deadline - clock.todayEpochDay();
        Object snapshot = row.snapshot() == null ? null : JsonSupport.readTree(row.snapshot());
        return new Results.FilingView(row.id(), row.formulaId(), row.productCode(),
                row.version(), row.status(), row.watchStartDay(), deadline, daysRemaining,
                row.suspendReason(), snapshot, row.createdAt(), row.finalizedAt(), replayed);
    }

    /**
     * requestId 幂等包装：指纹覆盖 requestId 与全部业务字段。
     * 先插入占位行（status_code=0）抢占 requestId，执行业务动作后回填首次响应；
     * 同键同参返回首次结果（含首次状态码），同键异参返回 409；
     * 业务动作抛错时外层事务回滚，占位行一并回滚，客户端可用同一 requestId 重试。
     */
    private <T> ServiceResult<T> withIdempotency(String requestId, Object params, Class<T> type,
            Supplier<Outcome<T>> action) {
        if (requestId == null || requestId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_ID_REQUIRED",
                    "变更接口必须提供 requestId");
        }
        String fingerprint = fingerprint(requestId, params);
        Optional<com.example.starter.compliance.repo.IdempotentRow> existing =
                repository.findIdempotent(requestId);
        if (existing.isPresent()) {
            return resolveIdempotent(existing.get(), fingerprint, type);
        }
        try {
            repository.insertIdempotent(requestId, fingerprint, 0, null, clock.nowMillis());
        } catch (DuplicateKeyException e) {
            // 并发同键：另一事务已占位或已完成，按其记录重放或报 409。
            com.example.starter.compliance.repo.IdempotentRow raced =
                    repository.findIdempotent(requestId)
                            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                                    "REQUEST_IN_PROGRESS", "同一 requestId 正在处理中"));
            return resolveIdempotent(raced, fingerprint, type);
        }

        Outcome<T> outcome = action.get();
        repository.completeIdempotent(requestId, outcome.status(),
                JsonSupport.write(outcome.body()));
        return new ServiceResult<>(outcome.body(), false, outcome.status());
    }

    private <T> ServiceResult<T> resolveIdempotent(
            com.example.starter.compliance.repo.IdempotentRow row, String fingerprint,
            Class<T> type) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_PARAM_CONFLICT",
                    "requestId 已用于不同业务参数");
        }
        if (row.statusCode() == 0 || row.responseJson() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                    "同一 requestId 的请求正在处理中");
        }
        return ServiceResult.replay(JsonSupport.readReplayed(row.responseJson(), type),
                row.statusCode());
    }

    private static String fingerprint(String requestId, Object params) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("requestId", requestId);
        material.put("params", params);
        String canonical = JsonSupport.canonical(material);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 业务动作结果：响应体 + 首次 HTTP 状态码（逾期自动暂停等场景首次结果即 409）。 */
    private record Outcome<T>(T body, int status) {
    }
}
