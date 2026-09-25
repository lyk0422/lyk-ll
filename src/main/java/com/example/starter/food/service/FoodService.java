package com.example.starter.food.service;

import com.example.starter.food.domain.ComplianceReport;
import com.example.starter.food.domain.FilingStatus;
import com.example.starter.food.domain.RecipeData;
import com.example.starter.food.domain.RecipeSnapshot;
import com.example.starter.food.domain.RecipeStatus;
import com.example.starter.food.repo.FilingRow;
import com.example.starter.food.repo.FoodRepository;
import com.example.starter.food.repo.RecipeRow;
import com.example.starter.food.web.Dtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 食品配方合规服务：提交 → 合规审查 → 批准 → 量产备案；标准更新触发复审观察/逾期暂停。
 * 所有时间均以 UTC 为准，复审窗口按 UTC 日界计算 90 个自然日（恰第 90 日受理）。
 */
@Service
public class FoodService {

    private final FoodRepository repo;
    private final ComplianceEvaluator evaluator;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;
    private final Clock clock;

    public FoodService(FoodRepository repo, ComplianceEvaluator evaluator,
                       IdempotencyService idempotency, ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.evaluator = evaluator;
        this.idempotency = idempotency;
        this.mapper = mapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    // ---------------- 配方提交 ----------------

    @Transactional
    public IdempotencyService.Result submitRecipe(Dtos.SubmitRecipeRequest req) {
        Object fingerprintParts = Map.of(
                "productCode", req.productCode(),
                "recipeVersion", req.recipeVersion(),
                "categoryCode", req.categoryCode(),
                "additives", req.additives() == null ? List.of() : req.additives(),
                "ingredients", req.ingredients() == null ? List.of() : req.ingredients());
        String fingerprint = Fingerprint.sha256(mapper, List.of(req.requestId(), fingerprintParts));
        return idempotency.execute(req.requestId(), fingerprintParts,
                () -> IdempotencyService.ServiceResult.ok(doSubmit(req, fingerprint)));
    }

    private Dtos.RecipeResponse doSubmit(Dtos.SubmitRecipeRequest req, String fingerprint) {
        Instant now = now();
        long recipeId = repo.insertRecipe(req.requestId(), req.productCode(), req.recipeVersion(),
                req.categoryCode(), fingerprint, now);
        List<Dtos.AdditiveInput> additives = req.additives() == null ? List.of() : req.additives();
        List<Dtos.IngredientInput> ingredients = req.ingredients() == null ? List.of() : req.ingredients();
        for (int i = 0; i < additives.size(); i++) {
            Dtos.AdditiveInput a = additives.get(i);
            if (repo.getAdditive(a.additiveCode()) == null) {
                throw new BusinessException(400, "未知添加剂编码: " + a.additiveCode());
            }
            repo.insertRecipeAdditive(recipeId, a.additiveCode(), a.amount(), i);
        }
        for (int i = 0; i < ingredients.size(); i++) {
            Dtos.IngredientInput ing = ingredients.get(i);
            if (ing.proportion().compareTo(BigDecimal.ONE) > 0) {
                throw new BusinessException(400, "复合配料占比不得大于 1: " + ing.compoundCode());
            }
            repo.insertRecipeIngredient(recipeId, ing.compoundCode(), ing.proportion(), i);
        }
        return toRecipeResponse(repo.findRecipeById(recipeId));
    }

    // ---------------- 合规审查 ----------------

    @Transactional
    public IdempotencyService.Result reviewRecipe(Dtos.ReviewRecipeRequest req, Long recipeId) {
        Instant now = now();
        String std = req.standardVersion() == null || req.standardVersion().isBlank()
                ? repo.latestStandardVersion(now) : req.standardVersion();
        if (std == null) {
            throw new BusinessException(400, "尚无已发布的添加剂使用标准");
        }
        if (!repo.standardExists(std)) {
            throw new BusinessException(404, "标准版本不存在: " + std);
        }
        Object fingerprintParts = Map.of("recipeId", recipeId, "standardVersion", std);
        String requestId = req.requestId();
        return idempotency.execute(requestId, fingerprintParts, () -> {
            RecipeRow row = mustRecipe(recipeId);
            if (row.status == RecipeStatus.APPROVED) {
                throw new BusinessException(409, "配方已批准，版本已冻结，不可再次审查");
            }
            if (row.status == RecipeStatus.REJECTED) {
                throw new BusinessException(409, "配方已被整单拒绝，请修改后以新 requestId 重新提交");
            }
            RecipeData data = loadRecipeData(row);
            ComplianceReport report = evaluator.evaluate(data, std);
            RecipeStatus status = report.compliant() ? RecipeStatus.COMPLIANT : RecipeStatus.REJECTED;
            repo.saveReviewResult(recipeId, status, writeJson(report), std, now);
            return IdempotencyService.ServiceResult.ok(toRecipeResponse(repo.findRecipeById(recipeId)));
        });
    }

    // ---------------- 批准（版本冻结） ----------------

    @Transactional
    public IdempotencyService.Result approveRecipe(String requestId, Long recipeId) {
        Object fingerprintParts = Map.of("recipeId", recipeId, "action", "APPROVE");
        return idempotency.execute(requestId, fingerprintParts, () -> {
            RecipeRow row = mustRecipe(recipeId);
            if (row.status == RecipeStatus.APPROVED) {
                return IdempotencyService.ServiceResult.ok(toRecipeResponse(row));
            }
            if (row.status != RecipeStatus.COMPLIANT) {
                throw new BusinessException(409, "仅合规审查通过的配方可以批准，当前状态=" + row.status);
            }
            Instant now = now();
            RecipeSnapshot snapshot = new RecipeSnapshot(row.productCode, row.recipeVersion, row.categoryCode,
                    row.reviewedStd, loadAdditives(row.id), loadIngredients(row.id));
            repo.approveRecipe(recipeId, writeJson(snapshot), row.reviewedStd, now);
            // 新版本批准与旧版本备案暂停在同一事务内完成，确保不出现双版本同时有效
            FilingRow effective = repo.findEffectiveFiling(row.productCode);
            if (effective != null) {
                repo.suspendFiling(effective.id,
                        "产品新版本 " + row.recipeVersion + " 已批准，旧版本备案同步暂停", now);
            }
            return IdempotencyService.ServiceResult.ok(toRecipeResponse(repo.findRecipeById(recipeId)));
        });
    }

    // ---------------- 量产备案 ----------------

    @Transactional
    public IdempotencyService.Result fileFiling(Dtos.FileFilingRequest req) {
        Object fingerprintParts = Map.of("recipeId", req.recipeId(), "action", "FILE");
        return idempotency.execute(req.requestId(), fingerprintParts, () -> {
            RecipeRow recipe = mustRecipe(req.recipeId());
            if (recipe.status != RecipeStatus.APPROVED) {
                throw new BusinessException(409, "仅已批准（版本冻结）的配方可以量产备案");
            }
            Instant now = now();
            FilingRow existing = repo.findEffectiveFiling(recipe.productCode);
            if (existing != null) {
                throw new BusinessException(409,
                        "产品 " + recipe.productCode + " 已存在有效备案版本 " + existing.recipeVersion
                                + "（备案号 " + existing.id + "），同一产品同时刻只允许一个有效备案版本");
            }
            long filingId;
            try {
                filingId = repo.insertFiling(req.requestId(), recipe.productCode, recipe.id, recipe.recipeVersion,
                        recipe.approvedStd, FilingStatus.ACTIVE, recipe.snapshotJson, now, null);
            } catch (org.springframework.dao.DuplicateKeyException dke) {
                // 并发备案：唯一有效占位 uk_filing_active 冲突，仅此一个事务成功
                throw new BusinessException(409,
                        "产品 " + recipe.productCode + " 并发备案冲突：同一产品同时刻只允许一个有效备案版本");
            }
            return IdempotencyService.ServiceResult.ok(toFilingResponse(repo.findFilingById(filingId)));
        });
    }

    // ---------------- 标准发布：旧备案进入复审观察 ----------------

    @Transactional
    public IdempotencyService.Result publishStandard(Dtos.PublishStandardRequest req, Instant publishedAt) {
        Instant pubAt = publishedAt != null ? publishedAt : now();
        // 指纹只含客户端业务字段（版本号），不含服务端赋予的发布时刻，保证正常重试可幂等重放
        Object fingerprintParts = Map.of("version", req.version());
        return idempotency.execute(req.requestId(), fingerprintParts, () -> {
            if (repo.standardExists(req.version())) {
                throw new BusinessException(409, "标准版本已存在: " + req.version());
            }
            repo.insertStandardVersion(req.version(), pubAt);
            LocalDate dueDate = pubAt.atZone(ZoneOffset.UTC).toLocalDate().plusDays(90);
            int affected = 0;
            for (FilingRow f : repo.listActiveFilings()) {
                if (!f.standardVersion.equals(req.version())) {
                    repo.markReviewPending(f.id, dueDate);
                    affected++;
                }
            }
            return IdempotencyService.ServiceResult.ok(
                    new Dtos.PublishStandardResponse(req.version(), pubAt,
                            dueDate.atStartOfDay(ZoneOffset.UTC).toInstant(), affected));
        });
    }

    // ---------------- 备案复审 ----------------

    @Transactional
    public IdempotencyService.Result reviewFiling(Dtos.ReviewFilingRequest req, Long filingId) {
        Object fingerprintParts = Map.of("filingId", filingId, "action", "REVIEW_FILING");
        return idempotency.execute(req.requestId(), fingerprintParts, () -> {
            FilingRow f = mustFiling(filingId);
            Instant now = now();
            LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();

            if (f.status == FilingStatus.ACTIVE && f.reviewedStd == null && f.reviewDueDate == null) {
                return IdempotencyService.ServiceResult.of(200, toFilingResponse(f));
            }
            if (f.status == FilingStatus.CANCELLED) {
                throw new BusinessException(409, "备案已注销（终态），不可复审");
            }
            if (f.status == FilingStatus.SUSPENDED) {
                throw new BusinessException(409, "备案已暂停，不可复审（暂停原因: " + f.suspendReason + "）");
            }
            if (f.status == FilingStatus.ACTIVE && f.reviewedStd != null) {
                // 已完成复审的幂等视图
                return IdempotencyService.ServiceResult.ok(toFilingResponse(f));
            }

            // REVIEW_PENDING：恰第 90 日（UTC 日界）受理；第 91 日起逾期，自动暂停后拒绝。
            // 暂停须与 409 响应在同一事务内持久化，故返回 409 结果而非抛异常（避免回滚暂停）。
            if (today.isAfter(f.reviewDueDate)) {
                repo.suspendFiling(f.id, "标准更新后超过 90 个自然日未完成复审（截止 "
                        + f.reviewDueDate + " UTC）", now);
                return IdempotencyService.ServiceResult.of(409, toFilingResponse(repo.findFilingById(f.id)));
            }

            String latestStd = repo.latestStandardVersion(now);
            RecipeSnapshot snapshot = readSnapshot(f.snapshotJson);
            ComplianceReport report = evaluator.evaluate(snapshot.toRecipeData(), latestStd);
            if (!report.compliant()) {
                repo.suspendFiling(f.id, "复审不合格：按新标准 " + latestStd + " 复算存在违例", now);
                return IdempotencyService.ServiceResult.of(409, toFilingResponse(repo.findFilingById(f.id)));
            }
            repo.markReviewedActive(f.id, latestStd, now);
            return IdempotencyService.ServiceResult.ok(toFilingResponse(repo.findFilingById(f.id)));
        });
    }

    /** 逾期未复审自动暂停扫描（可由定时任务或管理接口触发）。 */
    @Transactional
    public Dtos.SweepResponse sweepOverdue() {
        Instant now = now();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        int count = 0;
        for (FilingRow f : repo.listReviewPendingFilings()) {
            if (today.isAfter(f.reviewDueDate)) {
                repo.suspendFiling(f.id, "标准更新后超过 90 个自然日未完成复审（截止 "
                        + f.reviewDueDate + " UTC）", now);
                count++;
            }
        }
        return new Dtos.SweepResponse(now, count);
    }

    // ---------------- 注销（终态） ----------------

    @Transactional
    public IdempotencyService.Result cancelFiling(String requestId, Long filingId) {
        Object fingerprintParts = Map.of("filingId", filingId, "action", "CANCEL");
        return idempotency.execute(requestId, fingerprintParts, () -> {
            FilingRow f = mustFiling(filingId);
            if (f.status != FilingStatus.CANCELLED) {
                repo.cancelFiling(f.id, now());
            }
            // 重复注销返回幂等成功
            return IdempotencyService.ServiceResult.ok(toFilingResponse(repo.findFilingById(f.id)));
        });
    }

    // ---------------- 量产记录上报 ----------------

    @Transactional
    public IdempotencyService.Result reportProduction(Dtos.ProductionReportRequest req, Long filingId) {
        Object fingerprintParts = Map.of(
                "filingId", filingId,
                "batchNo", req.batchNo(),
                "quantityKg", req.quantityKg());
        return idempotency.execute(req.requestId(), fingerprintParts, () -> {
            FilingRow f = mustFiling(filingId);
            if (f.status == FilingStatus.CANCELLED) {
                throw new BusinessException(409, "备案已注销，量产记录上报一律拒绝");
            }
            if (f.status == FilingStatus.SUSPENDED) {
                throw new BusinessException(409, "备案已暂停，量产记录上报拒绝");
            }
            Instant now = now();
            long id = repo.insertProductionReport(f.id, req.batchNo(), req.quantityKg(), now);
            return IdempotencyService.ServiceResult.ok(
                    new Dtos.ProductionReportResponse(id, f.id, req.batchNo(), req.quantityKg(), now));
        });
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public Dtos.RecipeResponse getRecipe(long recipeId) {
        return toRecipeResponse(mustRecipe(recipeId));
    }

    @Transactional(readOnly = true)
    public Dtos.FilingResponse getFiling(long filingId) {
        return toFilingResponse(mustFiling(filingId));
    }

    // ---------------- 组装辅助 ----------------

    private RecipeRow mustRecipe(long id) {
        RecipeRow row = repo.findRecipeById(id);
        if (row == null) {
            throw new BusinessException(404, "配方不存在: id=" + id);
        }
        return row;
    }

    private FilingRow mustFiling(long id) {
        FilingRow f = repo.findFilingById(id);
        if (f == null) {
            throw new BusinessException(404, "备案不存在: id=" + id);
        }
        return f;
    }

    private RecipeData loadRecipeData(RecipeRow row) {
        return new RecipeData(row.categoryCode, loadAdditives(row.id), loadIngredients(row.id));
    }

    private List<RecipeData.DirectAdditive> loadAdditives(long recipeId) {
        List<RecipeData.DirectAdditive> list = new ArrayList<>();
        for (Map<String, Object> m : repo.additivesOf(recipeId)) {
            list.add(new RecipeData.DirectAdditive((String) m.get("additiveCode"), (BigDecimal) m.get("amount")));
        }
        return list;
    }

    private List<RecipeData.Ingredient> loadIngredients(long recipeId) {
        List<RecipeData.Ingredient> list = new ArrayList<>();
        for (Map<String, Object> m : repo.ingredientsOf(recipeId)) {
            list.add(new RecipeData.Ingredient((String) m.get("compoundCode"), (BigDecimal) m.get("proportion")));
        }
        return list;
    }

    private RecipeSnapshot readSnapshot(String json) {
        try {
            return mapper.readValue(json, RecipeSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("批准快照解析失败", e);
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Dtos.RecipeResponse toRecipeResponse(RecipeRow row) {
        List<Dtos.ComplianceItemResponse> items = new ArrayList<>();
        Boolean compliant = null;
        if (row.reviewDetail != null) {
            try {
                ComplianceReport report = mapper.readValue(row.reviewDetail, ComplianceReport.class);
                compliant = report.compliant();
                for (var it : report.items()) {
                    items.add(new Dtos.ComplianceItemResponse(it.itemType(), it.additiveCode(), it.additiveName(),
                            it.functionType(), it.source(), it.directAmount(), it.carryOverAmount(),
                            it.effectiveAmount(), it.maxAmount(), it.ratio(), it.pass(), it.message()));
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        return new Dtos.RecipeResponse(row.id, row.requestId, row.productCode, row.recipeVersion, row.categoryCode,
                row.status.name(), row.reviewedStd, compliant, row.createdAt, row.reviewedAt, row.approvedAt, items);
    }

    private Dtos.FilingResponse toFilingResponse(FilingRow f) {
        Instant due = f.reviewDueDate == null ? null
                : f.reviewDueDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        return new Dtos.FilingResponse(f.id, f.requestId, f.productCode, f.recipeId, f.recipeVersion,
                f.standardVersion, f.status.name(), f.filedAt, due, f.reviewedAt, f.reviewedStd,
                f.suspendedAt, f.suspendReason, f.cancelledAt);
    }
}
