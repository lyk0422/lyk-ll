package com.example.starter.compliance.service;

import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.repo.ComplianceRepository;
import com.example.starter.compliance.repo.FilingRow;
import com.example.starter.compliance.repo.FormulaAllergenRow;
import com.example.starter.compliance.repo.LineControlRepository;
import com.example.starter.compliance.repo.ProductionRecordRow;
import com.example.starter.compliance.repo.ReworkMaterialRow;
import com.example.starter.compliance.repo.ReworkRepository;
import com.example.starter.compliance.time.ComplianceClock;
import com.example.starter.compliance.web.Requests.RegisterReworkRequest;
import com.example.starter.compliance.web.Requests.ReworkUseDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 返工料领域服务：
 *
 * <p>登记引用一条本系统已成功量产记录（不能引用不存在的外部/他人配方记录），冻结来源配方版本及
 * 其已登记过敏原集合；唯一料号、同来源只登记一次；登记量为独立合成额度（不声称旧记录已有产量），
 * 正数且最多三位小数。料账版本从 1 开始，修改须携带 expectedVersion，成功加 1，旧版 409；
 * 修改仅可修正登记量（不得低于已消费量），不得换来源、不可清空过敏原，历史消费不可改。
 *
 * <p>受控量产投用：1~10 个不同料号，全部数为正且最多三位小数，返工合计不得超过总量 20%
 * （恰等允许，按精确值判定，不靠舍入豁免）；全部来源过敏原并集必须被目标配方版本声明，
 * 清线凭证只证明线路清洁，不能替代物料过敏原声明。受理时在同一事务内按料号排序逐行锁定、
 * 校验余额并原子扣减，任一不足或备案失效整笔零扣；两批争同料由行锁 + 条件扣减保证不得超用。
 *
 * <p>全部写操作以 requestId 幂等：指纹覆盖配方、来源集合、重量与精确时刻，同键同参重放首次
 * 冻结结果，异参 409，失败不占键。参数非法 400、对象不存在 404、业务拒绝 422。
 */
@Service
public class ReworkService {

    private final ReworkRepository reworkRepository;
    private final ComplianceRepository repository;
    private final LineControlRepository lineRepository;
    private final ComplianceClock clock;
    private final IdempotentExecutor idempotency;

    public ReworkService(ReworkRepository reworkRepository, ComplianceRepository repository,
            LineControlRepository lineRepository, ComplianceClock clock,
            IdempotentExecutor idempotency) {
        this.reworkRepository = reworkRepository;
        this.repository = repository;
        this.lineRepository = lineRepository;
        this.clock = clock;
        this.idempotency = idempotency;
    }

    // ---------------- 返工料登记（新增/修改） ----------------

    @Transactional
    public ServiceResult<ReworkResults.ReworkMaterialView> registerRework(
            RegisterReworkRequest request) {
        return idempotency.execute(request.requestId(), request,
                ReworkResults.ReworkMaterialView.class, () -> {
                    String materialCode = requireText(request.materialCode(), "料号不能为空");
                    if (request.expectedVersion() == null || request.expectedVersion() < 0) {
                        throw invalid("expectedVersion 不能为空且不能为负（新增传 0）");
                    }
                    if (request.registeredAt() == null || request.registeredAt() < 0) {
                        throw invalid("registeredAt 精确时刻不能为空");
                    }
                    BigDecimal availableKg = requirePositiveKg(request.availableKg(), "登记可用公斤数");
                    long now = clock.nowMillis();

                    if (request.expectedVersion() == 0) {
                        createMaterial(request, materialCode, availableKg, now);
                    } else {
                        updateMaterial(request, materialCode, availableKg, now);
                    }
                    ReworkMaterialRow saved =
                            reworkRepository.findMaterialByCode(materialCode).orElseThrow();
                    return new IdempotentExecutor.Outcome<>(buildMaterialView(saved, false),
                            HttpStatus.OK.value());
                });
    }

    private void createMaterial(RegisterReworkRequest request, String materialCode,
            BigDecimal availableKg, long now) {
        if (request.productionRecordId() == null) {
            throw invalid("新增返工料必须引用来源量产记录 productionRecordId");
        }
        if (reworkRepository.findMaterialByCode(materialCode).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "REWORK_EXISTS",
                    "料号已登记：" + materialCode);
        }
        // 必须引用本系统已成功量产记录：不存在的记录（含外部/他人配方记录）一律 404。
        ProductionRecordRow source = repository
                .findProductionRecordById(request.productionRecordId())
                .orElseThrow(() -> notFound("REWORK_SOURCE_NOT_FOUND",
                        "来源量产记录不存在：" + request.productionRecordId()));
        if (reworkRepository.findMaterialByProductionRecordId(source.id()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "REWORK_SOURCE_ALREADY_REGISTERED",
                    "同一来源量产记录只登记一次：" + source.id());
        }
        FilingRow filing = repository.findFilingById(source.filingId())
                .orElseThrow(() -> notFound("REWORK_SOURCE_NOT_FOUND",
                        "来源量产记录对应备案不存在：" + source.filingId()));
        // 冻结来源配方版本及其过敏原集合；来源版本未登记过敏原则无法冻结，拒绝登记。
        FormulaAllergenRow allergenRow = lineRepository
                .findFormulaAllergen(filing.productCode(), filing.version())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "REWORK_SOURCE_ALLERGEN_NOT_REGISTERED",
                        "来源配方版本尚未登记过敏原集合，无法冻结：" + filing.productCode() + "#"
                                + filing.version()));
        try {
            reworkRepository.insertMaterial(materialCode, source.id(), filing.productCode(),
                    filing.version(), allergenRow.allergens(), availableKg,
                    request.requestId(), request.registeredAt(), now);
        } catch (DuplicateKeyException e) {
            // 并发登记：唯一料号 / 唯一来源约束兜底，仅一方成功。
            if (reworkRepository.findMaterialByCode(materialCode).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "REWORK_EXISTS",
                        "料号已登记：" + materialCode);
            }
            throw new ApiException(HttpStatus.CONFLICT, "REWORK_SOURCE_ALREADY_REGISTERED",
                    "同一来源量产记录只登记一次：" + source.id());
        }
    }

    private void updateMaterial(RegisterReworkRequest request, String materialCode,
            BigDecimal availableKg, long now) {
        ReworkMaterialRow existing = reworkRepository.findMaterialByCode(materialCode)
                .orElseThrow(() -> notFound("REWORK_NOT_FOUND", "返工料不存在：" + materialCode));
        if (existing.version() != request.expectedVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_VERSION_CONFLICT",
                    "料账版本已变化：expectedVersion=" + request.expectedVersion()
                            + "，当前版本=" + existing.version());
        }
        if (request.productionRecordId() != null
                && !request.productionRecordId().equals(existing.productionRecordId())) {
            // 料号不得换来源；过敏原集合随来源冻结，同样不可清空或改写。
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REWORK_SOURCE_IMMUTABLE",
                    "料号不得换来源：原来源量产记录 " + existing.productionRecordId());
        }
        if (availableKg.compareTo(existing.consumedKg()) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "REWORK_AMOUNT_BELOW_CONSUMED",
                    "修正后的登记量不得低于已消费量 " + existing.consumedKg().toPlainString());
        }
        if (!reworkRepository.updateAvailableKg(existing.id(), existing.version(), availableKg,
                now)) {
            // 并发修改：乐观版本条件失配，整事务回滚，仅一方提交。
            throw new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_VERSION_CONFLICT",
                    "返工料账并发修改冲突");
        }
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public ReworkResults.ReworkMaterialView getMaterial(String materialCode) {
        ReworkMaterialRow row = reworkRepository.findMaterialByCode(materialCode)
                .orElseThrow(() -> notFound("REWORK_NOT_FOUND", "返工料不存在：" + materialCode));
        return buildMaterialView(row, false);
    }

    @Transactional(readOnly = true)
    public ReworkResults.ReworkConsumptionListView getConsumptions(String materialCode) {
        ReworkMaterialRow row = reworkRepository.findMaterialByCode(materialCode)
                .orElseThrow(() -> notFound("REWORK_NOT_FOUND", "返工料不存在：" + materialCode));
        List<ReworkResults.ReworkConsumptionView> consumptions =
                reworkRepository.findConsumptionsByMaterialId(row.id()).stream()
                        .map(c -> new ReworkResults.ReworkConsumptionView(c.id(),
                                c.productionRecordId(),
                                c.weightKg().stripTrailingZeros().toPlainString(),
                                c.consumedAt()))
                        .toList();
        return new ReworkResults.ReworkConsumptionListView(materialCode, consumptions);
    }

    // ---------------- 受控量产投用（在 LineControlService 事务内调用） ----------------

    /**
     * 投用参数校验：1~10 个不同料号；总公斤数与全部投用重量均为正且最多三位小数；
     * 返工合计不得超过总量 20%（恰等允许，按精确值判定，不靠舍入豁免）。
     */
    public void validateReworkParams(List<ReworkUseDto> reworks, BigDecimal quantityKg) {
        if (reworks == null || reworks.isEmpty()) {
            throw invalid("投用返工料时 reworks 不能为空");
        }
        if (reworks.size() > 10) {
            throw invalid("单次量产最多投用 10 个不同返工料");
        }
        requirePositiveKg(quantityKg, "量产总公斤数");
        Set<String> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ReworkUseDto use : reworks) {
            if (use == null) {
                throw invalid("返工料投用项不能为空");
            }
            String materialCode = requireText(use.materialCode(), "返工料号不能为空");
            if (!seen.add(materialCode)) {
                throw invalid("同一量产中返工料号不得重复：" + materialCode);
            }
            total = total.add(requirePositiveKg(use.weightKg(), "返工料投用重量"));
        }
        // 合计 ≤ 总量 × 20%：两边同乘 5 精确比较（合计 × 5 ≤ 总量），恰等允许。
        if (total.multiply(BigDecimal.valueOf(5)).compareTo(quantityKg) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REWORK_QUOTA_EXCEEDED",
                    "返工料合计 " + total.stripTrailingZeros().toPlainString()
                            + " 超过总量 20% 上限 "
                            + quantityKg.multiply(new BigDecimal("0.2"))
                                    .stripTrailingZeros().toPlainString());
        }
    }

    /**
     * 锁定并校验投用：按料号排序逐行加锁（避免死锁），料号必须存在；
     * 全部来源过敏原并集必须被目标配方版本声明（清线凭证不能替代物料过敏原声明）；
     * 逐料余额必须充足。返回按料号排序的锁定行。
     */
    public List<ReworkMaterialRow> lockAndCheck(List<ReworkUseDto> reworks,
            List<String> declaredAllergens) {
        Map<String, ReworkMaterialRow> locked = new LinkedHashMap<>();
        List<String> sortedCodes = reworks.stream().map(use -> use.materialCode().trim())
                .sorted().toList();
        for (String code : sortedCodes) {
            ReworkMaterialRow row = reworkRepository.findMaterialByCodeForUpdate(code)
                    .orElseThrow(() -> notFound("REWORK_NOT_FOUND", "返工料不存在：" + code));
            locked.put(code, row);
        }
        Set<String> declared = new HashSet<>(declaredAllergens);
        Set<String> missing = new TreeSet<>();
        for (ReworkMaterialRow row : locked.values()) {
            for (String allergen : row.allergens()) {
                if (!declared.contains(allergen)) {
                    missing.add(allergen);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "REWORK_ALLERGEN_NOT_DECLARED",
                    "返工料来源过敏原未被目标配方版本声明：" + new ArrayList<>(missing));
        }
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (ReworkUseDto use : reworks) {
            weights.put(use.materialCode().trim(), use.weightKg());
        }
        for (Map.Entry<String, ReworkMaterialRow> entry : locked.entrySet()) {
            ReworkMaterialRow row = entry.getValue();
            BigDecimal weight = weights.get(entry.getKey());
            BigDecimal balance = row.availableKg().subtract(row.consumedKg());
            if (balance.compareTo(weight) < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "REWORK_BALANCE_INSUFFICIENT",
                        "返工料 " + entry.getKey() + " 余额不足：可用 "
                                + balance.stripTrailingZeros().toPlainString() + "，需投用 "
                                + weight.stripTrailingZeros().toPlainString());
            }
        }
        return List.copyOf(locked.values());
    }

    /**
     * 原子扣减并写消费流水：条件扣减兜底（并发下余额不足则失败，整笔量产回滚零扣）；
     * 返回投用明细视图。调用方须先经 {@link #lockAndCheck} 锁定同一批料账行。
     */
    public List<LineControlResults.ReworkUseView> deduct(List<ReworkMaterialRow> locked,
            List<ReworkUseDto> reworks, long productionRecordId, long reportedAt) {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (ReworkUseDto use : reworks) {
            weights.put(use.materialCode().trim(), use.weightKg());
        }
        List<LineControlResults.ReworkUseView> views = new ArrayList<>(locked.size());
        for (ReworkMaterialRow row : locked) {
            BigDecimal weight = weights.get(row.materialCode());
            if (!reworkRepository.consumeMaterial(row.id(), weight, reportedAt)) {
                // 行锁内仍不足只会发生在极端并发场景：整事务回滚，全部零扣。
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "REWORK_BALANCE_INSUFFICIENT",
                        "返工料 " + row.materialCode() + " 余额已被并发耗用，整笔回滚");
            }
            reworkRepository.insertConsumption(row.id(), productionRecordId, weight, reportedAt);
            views.add(new LineControlResults.ReworkUseView(row.materialCode(),
                    weight.stripTrailingZeros().toPlainString()));
        }
        return views;
    }

    // ---------------- 内部辅助 ----------------

    /** 正数且最多三位小数（按数值精度判定，尾随零不影响）。 */
    private static BigDecimal requirePositiveKg(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            throw invalid(label + "必须为正数");
        }
        if (value.stripTrailingZeros().scale() > 3) {
            throw invalid(label + "最多三位小数：" + value.toPlainString());
        }
        return value;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw invalid(message);
        }
        return value.trim();
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM", message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private static ReworkResults.ReworkMaterialView buildMaterialView(ReworkMaterialRow row,
            boolean replayed) {
        BigDecimal balance = row.availableKg().subtract(row.consumedKg());
        return new ReworkResults.ReworkMaterialView(row.id(), row.materialCode(),
                row.productionRecordId(), row.sourceProductCode(), row.sourceFormulaVersion(),
                List.copyOf(row.allergens()),
                row.availableKg().stripTrailingZeros().toPlainString(),
                row.consumedKg().stripTrailingZeros().toPlainString(),
                balance.stripTrailingZeros().toPlainString(),
                row.version(), row.registeredAt(), row.updatedAt(), replayed);
    }
}
