package com.example.starter.feed.service;

import com.example.starter.feed.domain.FormulaStatus;
import com.example.starter.feed.dto.FormulaApproveRequest;
import com.example.starter.feed.dto.FormulaSubmitRequest;
import com.example.starter.feed.dto.FormulaSubmitResponse;
import com.example.starter.feed.dto.FormulaVersionTrailResponse;
import com.example.starter.feed.dto.RatioItem;
import com.example.starter.feed.repo.DictRepository;
import com.example.starter.feed.repo.FormulaRepository;
import com.example.starter.feed.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配方提交→合规校验→批准→投产流转中的提交、合规校验与批准环节。
 */
@Service
public class FormulaService {

    /** 幂等请求类型：配方提交。 */
    public static final String KIND_FORMULA_SUBMIT = "FORMULA_SUBMIT";

    private final FormulaRepository formulaRepository;
    private final DictRepository dictRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public FormulaService(FormulaRepository formulaRepository,
                          DictRepository dictRepository,
                          IdempotencyService idempotencyService,
                          Clock clock) {
        this.formulaRepository = formulaRepository;
        this.dictRepository = dictRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 提交配方并立即执行合规校验：
     * 药物不超上限（恰等合法）、禁忌组合拒绝、含药配方必须绑定允许的目标动物种类。
     * 返回幂等执行结果（含首次 HTTP 状态与重放标记）。
     */
    public IdempotencyService.Result<FormulaSubmitResponse> submit(FormulaSubmitRequest request) {
        List<RatioItem> ingredients = nullToEmpty(request.ingredients());
        List<RatioItem> drugs = nullToEmpty(request.drugs());
        validateNoDuplicates(ingredients, "原料");
        validateNoDuplicates(drugs, "药物添加剂");

        IdempotencyService.Result<FormulaSubmitResponse> result = idempotencyService.execute(
                request.requestId(),
                KIND_FORMULA_SUBMIT,
                request,
                () -> doSubmit(request, ingredients, drugs),
                FormulaSubmitResponse.class);
        if (result.replayed() && !result.response().replayed()) {
            FormulaSubmitResponse r = result.response();
            return new IdempotencyService.Result<>(
                    new FormulaSubmitResponse(r.formulaCode(), r.version(), r.status(), r.targetSpecies(),
                            r.ingredients(), r.drugs(), r.rejectReason(), true, r.createdAtUtc()),
                    result.httpStatus(), true);
        }
        return result;
    }

    private IdempotencyService.Outcome doSubmit(FormulaSubmitRequest request,
                                                List<RatioItem> ingredients,
                                                List<RatioItem> drugs) {
        String rejectReason = complianceCheck(request, ingredients, drugs);
        boolean medicated = !drugs.isEmpty();
        String status = rejectReason == null
                ? FormulaStatus.SUBMITTED.name()
                : FormulaStatus.REJECTED.name();

        Instant now = clock.instant();
        int version = formulaRepository.maxVersion(request.formulaCode()).orElse(0) + 1;
        long formulaId = insertFormulaWithRetry(request, version, medicated, status, rejectReason, now);
        for (RatioItem item : ingredients) {
            formulaRepository.insertIngredient(formulaId, item.code(), item.ratio());
        }
        for (RatioItem item : drugs) {
            formulaRepository.insertDrug(formulaId, item.code(), item.ratio());
        }

        Map<String, DictRepository.DrugDict> dict = dictRepository.findDrugs(
                        drugs.stream().map(RatioItem::code).distinct().toList())
                .stream().collect(Collectors.toMap(DictRepository.DrugDict::code, Function.identity()));

        List<FormulaSubmitResponse.DrugView> drugViews = drugs.stream()
                .map(d -> {
                    DictRepository.DrugDict dObj = dict.get(d.code());
                    return new FormulaSubmitResponse.DrugView(
                            d.code(), d.ratio(),
                            dObj == null ? null : dObj.maxRatio(),
                            dObj == null ? null : dObj.withdrawalDays());
                })
                .toList();

        FormulaSubmitResponse response = new FormulaSubmitResponse(
                request.formulaCode(),
                version,
                status,
                request.targetSpecies(),
                List.copyOf(ingredients),
                drugViews,
                rejectReason,
                false,
                now);
        // 合规拒绝落库为 REJECTED 版本并以 422 返回；幂等重放同样返回首次拒绝结果。
        int httpStatus = rejectReason == null ? 200 : 422;
        return new IdempotencyService.Outcome(response, httpStatus);
    }

    /**
     * 合规校验，返回拒绝原因；通过返回 null。
     */
    private String complianceCheck(FormulaSubmitRequest request, List<RatioItem> ingredients, List<RatioItem> drugs) {
        List<String> problems = new ArrayList<>();

        // 普通原料必须存在于合成字典。
        if (!ingredients.isEmpty()) {
            List<String> ingredientCodes = ingredients.stream().map(RatioItem::code).distinct().toList();
            long found = dictRepository.countIngredients(ingredientCodes);
            if (found != ingredientCodes.size()) {
                problems.add("存在未知原料编码");
            }
        }

        List<String> drugCodes = drugs.stream().map(RatioItem::code).distinct().toList();
        Map<String, DictRepository.DrugDict> drugDict = dictRepository.findDrugs(drugCodes).stream()
                .collect(Collectors.toMap(DictRepository.DrugDict::code, Function.identity()));

        for (RatioItem drug : drugs) {
            DictRepository.DrugDict dict = drugDict.get(drug.code());
            if (dict == null || !dict.active()) {
                problems.add("药物添加剂不存在或已停用: " + drug.code());
                continue;
            }
            // 恰等于上限合法，超过拒绝。
            if (drug.ratio().compareTo(dict.maxRatio()) > 0) {
                problems.add("药物 " + drug.code() + " 添加比例 " + drug.ratio().stripTrailingZeros().toPlainString()
                        + " 超过最大添加比例 " + dict.maxRatio().stripTrailingZeros().toPlainString());
            }
        }

        // 配伍禁忌组合直接拒绝。
        if (dictRepository.countContraindicationPairs(drugCodes) > 0) {
            problems.add("命中配伍禁忌组合: " + String.join("、", drugCodes));
        }

        // 含药配方必须绑定目标动物种类，且每种药物均允许该种类；错配拒绝。
        if (!drugs.isEmpty()) {
            String species = request.targetSpecies();
            if (species == null || species.isBlank()) {
                problems.add("含药配方必须绑定目标动物种类");
            } else {
                for (String drugCode : drugCodes) {
                    DictRepository.DrugDict dict = drugDict.get(drugCode);
                    if (dict == null || !dict.active()) {
                        continue;
                    }
                    List<String> allowed = dictRepository.findAllowedSpecies(drugCode);
                    if (!allowed.contains(species)) {
                        problems.add("药物 " + drugCode + " 不适用于目标动物种类 " + species);
                    }
                }
            }
        }

        return problems.isEmpty() ? null : String.join("；", problems);
    }

    /**
     * 批准合规校验通过的版本；批准即冻结。已批准/已投产版本重复批准不改变状态。
     */
    @Transactional
    public FormulaVersionTrailResponse.VersionView approve(FormulaApproveRequest request) {
        FormulaRepository.FormulaRow row = formulaRepository.get(request.formulaCode(), request.version());
        return switch (FormulaStatus.valueOf(row.status())) {
            case SUBMITTED -> {
                int updated = formulaRepository.markApproved(row.id(), clock.instant());
                if (updated == 0) {
                    throw ApiException.conflict("配方版本状态已被并发改变，请重新查询");
                }
                yield toVersionView(formulaRepository.get(request.formulaCode(), request.version()));
            }
            // 批准后版本冻结；重复批准直接返回当前冻结视图。
            case APPROVED, IN_PRODUCTION -> toVersionView(row);
            case REJECTED -> throw ApiException.unprocessable(
                    "合规校验拒绝的版本不可批准: " + request.formulaCode() + " v" + request.version()
                            + "，原因: " + row.rejectReason());
        };
    }

    /**
     * 版本轨迹：各版本独立校验、冻结，可复算。
     */
    @Transactional(readOnly = true)
    public FormulaVersionTrailResponse trail(String formulaCode) {
        List<FormulaRepository.FormulaRow> rows = formulaRepository.listVersions(formulaCode);
        if (rows.isEmpty()) {
            throw ApiException.notFound("配方不存在: " + formulaCode);
        }
        List<FormulaVersionTrailResponse.VersionView> views = rows.stream().map(this::toVersionView).toList();
        return new FormulaVersionTrailResponse(formulaCode, views);
    }

    private FormulaVersionTrailResponse.VersionView toVersionView(FormulaRepository.FormulaRow row) {
        return new FormulaVersionTrailResponse.VersionView(
                row.version(),
                row.name(),
                row.targetSpecies(),
                row.containsDrug(),
                row.status(),
                row.rejectReason(),
                row.approvedAtUtc(),
                row.firstProducedAtUtc(),
                row.createdAtUtc());
    }

    private long insertFormulaWithRetry(FormulaSubmitRequest request, int version, boolean medicated,
                                        String status, String rejectReason, Instant now) {
        int attempt = 0;
        while (true) {
            try {
                Long id = formulaRepository.insertFormula(now, request.formulaCode(), version,
                        request.name(), request.targetSpecies(), medicated, status, rejectReason,
                        request.requestId(), FingerprintUtil.sha256(request));
                return id;
            } catch (DuplicateKeyException e) {
                // 并发提交同一配方编码：重新取最大版本号后重试。
                if (++attempt > 10) {
                    throw ApiException.conflict("配方版本分配冲突，重试次数耗尽");
                }
                version = formulaRepository.maxVersion(request.formulaCode()).orElse(0) + 1;
            }
        }
    }

    private void validateNoDuplicates(List<RatioItem> items, String label) {
        Set<String> seen = new HashSet<>();
        for (RatioItem item : items) {
            if (!seen.add(item.code())) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        label + "清单中存在重复编码: " + item.code());
            }
        }
    }

    private List<RatioItem> nullToEmpty(List<RatioItem> items) {
        return items == null ? List.of() : items;
    }
}
