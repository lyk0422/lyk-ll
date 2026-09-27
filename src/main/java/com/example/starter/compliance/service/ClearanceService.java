package com.example.starter.compliance.service;

import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.repo.ClearanceCertificateRow;
import com.example.starter.compliance.repo.ControlledProductionRow;
import com.example.starter.compliance.repo.FilingRow;
import com.example.starter.compliance.repo.FormulaAllergenRegRow;
import com.example.starter.compliance.repo.FormulaRow;
import com.example.starter.compliance.repo.ProductionLineRow;
import com.example.starter.compliance.support.JsonSupport;
import com.example.starter.compliance.time.ComplianceClock;
import com.example.starter.compliance.web.Requests.ClearanceCertificateRequest;
import com.example.starter.compliance.web.Requests.ControlledProductionRequest;
import com.example.starter.compliance.web.Requests.FormulaAllergenRequest;
import com.example.starter.compliance.web.Requests.ProductionLineRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 换配方清线门禁服务（合成规则，仅演示，不用于生产）。
 *
 * <p>主线：为批准配方版本登记过敏原集合、为生产线登记最近量产版本与递增占用版本；
 * 双人确认清线凭证（固定线路、清线时刻与当时占用版本，UTC 确认后 2 小时内有效、恰满 2 小时失效）；
 * 受控量产声明携带线路与当前占用版本，当前批未声明而前批含有的过敏原必须由有效凭证覆盖，
 * 受理时原子写生产记录、推进最近配方与占用版本、核销凭证；任一校验失败整事务回滚、不消耗凭证。
 *
 * <p>并发：同一线路事务内先对生产线加行锁并以占用版本条件更新，两笔并发量产最多一笔使用同一占用版本；
 * 受理前对备案加行锁，使备案暂停/注销与量产之间存在唯一提交顺序。历史生产记录与批准配方快照不改。
 *
 * <p>所有变更接口以 requestId 幂等（见 {@link IdempotencySupport}）：完整参数（含版本、集合、精确时刻）
 * 同键同参重放首次冻结结果，异参 409，失败不占键。
 */
@Service
public class ClearanceService {

    /** 清线凭证有效期：UTC 确认后 2 小时，恰满 2 小时失效（validUntil 时刻已不可用）。 */
    public static final long CLEARANCE_VALIDITY_MILLIS = 2L * 60L * 60L * 1000L;

    private final com.example.starter.compliance.repo.ComplianceRepository repository;
    private final ComplianceClock clock;
    private final IdempotencySupport idempotency;
    private final FilingTransitions transitions;

    public ClearanceService(
            com.example.starter.compliance.repo.ComplianceRepository repository,
            ComplianceClock clock, IdempotencySupport idempotency, FilingTransitions transitions) {
        this.repository = repository;
        this.clock = clock;
        this.idempotency = idempotency;
        this.transitions = transitions;
    }

    // ---------------- 登记：配方过敏原 / 生产线 ----------------

    @Transactional
    public ServiceResult<Results.FormulaAllergenView> registerFormulaAllergens(
            FormulaAllergenRequest request) {
        return idempotency.execute(request.requestId(), request, Results.FormulaAllergenView.class,
                () -> {
                    FormulaRow formula = requireFormula(request.formulaId());
                    List<String> allergens = normalizeAllergenCodes(request.allergens());
                    long now = clock.nowMillis();

                    if (request.expectedVersion() == null) {
                        if (repository.findFormulaAllergenReg(formula.id()).isPresent()) {
                            throw new ApiException(HttpStatus.CONFLICT, "ALLERGEN_REG_EXISTS",
                                    "配方过敏原已登记，修改必须携带 expectedVersion");
                        }
                        repository.insertFormulaAllergenReg(formula.id(), request.requestId(), now);
                    } else {
                        FormulaAllergenRegRow reg =
                                requireAllergenReg(formula.id());
                        if (reg.version() != request.expectedVersion()) {
                            throw new ApiException(HttpStatus.CONFLICT, "ALLERGEN_VERSION_CONFLICT",
                                    "expectedVersion 与当前版本不一致：current=" + reg.version());
                        }
                        // 乐观条件更新：并发修改导致版本不再匹配时整事务回滚，仅一方成功。
                        int updated = repository.bumpFormulaAllergenRegVersion(formula.id(),
                                request.expectedVersion(), request.requestId(), now);
                        if (updated == 0) {
                            throw new ApiException(HttpStatus.CONFLICT, "ALLERGEN_VERSION_CONFLICT",
                                    "配方过敏原登记已被并发修改，请重试");
                        }
                        repository.deleteFormulaAllergenItems(formula.id());
                    }
                    for (String code : allergens) {
                        repository.insertFormulaAllergenItem(formula.id(), code);
                    }
                    FormulaAllergenRegRow saved =
                            repository.findFormulaAllergenReg(formula.id()).orElseThrow();
                    return IdempotencySupport.Outcome.ok(buildAllergenView(saved, false));
                });
    }

    @Transactional
    public ServiceResult<Results.ProductionLineView> registerLine(ProductionLineRequest request) {
        return idempotency.execute(request.requestId(), request, Results.ProductionLineView.class,
                () -> {
                    if (request.lineCode() == null || request.lineCode().isBlank()) {
                        throw invalid("生产线编码不能为空");
                    }
                    if (request.lineName() == null || request.lineName().isBlank()) {
                        throw invalid("生产线名称不能为空");
                    }
                    long now = clock.nowMillis();
                    try {
                        repository.insertProductionLine(request.lineCode(), request.lineName(), now);
                    } catch (DuplicateKeyException e) {
                        throw new ApiException(HttpStatus.CONFLICT, "LINE_EXISTS",
                                "生产线已登记：" + request.lineCode());
                    }
                    return IdempotencySupport.Outcome.ok(
                            buildLineView(repository.findProductionLine(request.lineCode())
                                    .orElseThrow()));
                });
    }

    // ---------------- 清线凭证双人确认 ----------------

    @Transactional
    public ServiceResult<Results.ClearanceCertificateView> confirmClearance(
            ClearanceCertificateRequest request) {
        return idempotency.execute(request.requestId(), request,
                Results.ClearanceCertificateView.class, () -> {
                    if (request.lineCode() == null || request.lineCode().isBlank()) {
                        throw invalid("生产线编码不能为空");
                    }
                    // 固定当时占用版本：对生产线加锁后读取，避免确认与量产提交互相穿插。
                    ProductionLineRow line =
                            repository.findProductionLineForUpdate(request.lineCode())
                                    .orElseThrow(() -> notFoundLine(request.lineCode()));
                    if (request.operatorId() == null || request.operatorId().isBlank()) {
                        throw invalid("操作人工号不能为空");
                    }
                    if (request.reviewerId() == null || request.reviewerId().isBlank()) {
                        throw invalid("复核人工号不能为空");
                    }
                    if (request.operatorId().equals(request.reviewerId())) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "CLEARANCE_TWO_PERSON_REQUIRED",
                                "清线凭证必须由操作人与复核人两名不同人员共同确认");
                    }
                    if (request.cleanedAt() == null) {
                        throw invalid("清线时刻 cleanedAt 不能为空（UTC epoch 毫秒）");
                    }
                    if (request.confirmedAt() == null) {
                        throw invalid("确认时刻 confirmedAt 不能为空（UTC epoch 毫秒）");
                    }
                    if (request.cleanedAt() > request.confirmedAt()) {
                        throw invalid("清线时刻不能晚于双人确认时刻");
                    }
                    List<String> covered = normalizeAllergenCodes(request.coveredAllergens());
                    if (covered.isEmpty()) {
                        throw invalid("清线凭证覆盖过敏原集合不能为空");
                    }
                    long now = clock.nowMillis();
                    long id = repository.insertClearanceCertificate(line.lineCode(),
                            request.operatorId(), request.reviewerId(), request.cleanedAt(),
                            request.confirmedAt(), line.occupiedVersion(),
                            JsonSupport.write(covered), request.requestId(), now);
                    return IdempotencySupport.Outcome.ok(buildCertificateView(
                            repository.findClearanceCertificate(id).orElseThrow(), false, now));
                });
    }

    // ---------------- 受控量产（门禁受理） ----------------

    @Transactional
    public ServiceResult<Results.ControlledProductionView> declareControlledProduction(
            ControlledProductionRequest request) {
        return idempotency.execute(request.requestId(), request,
                Results.ControlledProductionView.class, () -> {
                    if (request.lineCode() == null || request.lineCode().isBlank()) {
                        throw invalid("生产线编码不能为空");
                    }
                    if (request.filingId() == null) {
                        throw invalid("filingId 不能为空");
                    }
                    if (request.batchNo() == null || request.batchNo().isBlank()) {
                        throw invalid("批次号不能为空");
                    }
                    if (request.quantityKg() == null || request.quantityKg().signum() < 0) {
                        throw invalid("量产数量必须为非负数");
                    }
                    if (request.occupiedVersion() == null) {
                        throw invalid("必须显式声明生产线当前占用版本 occupiedVersion");
                    }
                    if (request.occurredAt() == null) {
                        throw invalid("必须提供精确生产时刻 occurredAt（UTC epoch 毫秒）");
                    }

                    // 逾期观察备案在加锁前以独立事务立即暂停（与旧入口一致），避免 REQUIRES_NEW 自锁。
                    FilingRow preliminary = repository.findFilingById(request.filingId())
                            .orElseThrow(() -> notFoundFiling(request.filingId()));
                    if (isOverdueWatch(preliminary)) {
                        transitions.suspendOverdueNewTransaction(preliminary);
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "FILING_NOT_EFFECTIVE",
                                "备案复审观察已逾期并自动暂停，受控量产拒绝");
                    }

                    // 统一锁顺序：先生产线后备案，串行化同一线路并发量产并与暂停/注销决出唯一提交顺序。
                    ProductionLineRow line = repository.findProductionLineForUpdate(
                            request.lineCode()).orElseThrow(() -> notFoundLine(request.lineCode()));
                    FilingRow filing = repository.findFilingByIdForUpdate(request.filingId())
                            .orElseThrow(() -> notFoundFiling(request.filingId()));
                    requireEffectiveFiling(filing);

                    if (line.occupiedVersion() != request.occupiedVersion()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "OCCUPIED_VERSION_MISMATCH",
                                "声明占用版本与线路当前占用版本不一致：current="
                                        + line.occupiedVersion());
                    }

                    FormulaRow currentFormula = repository.findFormulaById(filing.formulaId())
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "FORMULA_NOT_FOUND", "备案对应配方不存在：" + filing.formulaId()));
                    if (!"APPROVED".equals(currentFormula.state())) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "FORMULA_NOT_APPROVED", "仅批准配方版本可受控量产");
                    }
                    List<String> currentAllergens =
                            requireAllergenReg(currentFormula.id()).allergens();

                    Long certificateId = null;
                    Long previousFormulaId = line.lastFormulaId();
                    if (previousFormulaId != null
                            && previousFormulaId.longValue() != currentFormula.id()) {
                        List<String> previousAllergens =
                                requireAllergenReg(previousFormulaId).allergens();
                        Set<String> uncovered = new TreeSet<>(previousAllergens);
                        currentAllergens.forEach(uncovered::remove);
                        if (!uncovered.isEmpty()) {
                            certificateId = requireValidCertificate(request, line,
                                    uncovered, request.occurredAt());
                        } else if (request.certificateId() != null) {
                            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "CERTIFICATE_NOT_REQUIRED",
                                    "前批过敏原均已被新批声明，该切换无需清线凭证");
                        }
                    } else if (request.certificateId() != null) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "CERTIFICATE_NOT_REQUIRED",
                                "线路最近量产版本与本批一致或尚无在产批次，无需清线凭证");
                    }

                    long now = clock.nowMillis();
                    long recordId = repository.insertControlledProduction(line.lineCode(),
                            filing.id(), currentFormula.id(), request.batchNo(),
                            request.quantityKg(), line.occupiedVersion(), certificateId,
                            request.occurredAt(), request.requestId(), now);
                    if (certificateId != null) {
                        int consumed = repository.consumeClearanceCertificate(certificateId,
                                recordId, now);
                        if (consumed == 0) {
                            // 行锁下不应发生；任何核销失败都抛出以回滚记录，保证失败不消耗凭证。
                            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "CERTIFICATE_NOT_VALID", "凭证已被前序量产核销");
                        }
                    }
                    int advanced = repository.advanceProductionLineForProduction(line.lineCode(),
                            currentFormula.id(), request.occupiedVersion(), now);
                    if (advanced == 0) {
                        // 并发败者：占用版本已被先提交事务推进，整事务回滚，记录与凭证核销一并撤销。
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "OCCUPIED_VERSION_MISMATCH",
                                "并发量产冲突：同一线路占用版本已被先提交事务使用");
                    }

                    ControlledProductionRow saved =
                            repository.findControlledProduction(recordId).orElseThrow();
                    return IdempotencySupport.Outcome.ok(buildProductionView(saved, false));
                });
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<Results.AllergenView> listAllergens() {
        return repository.findAllAllergens().stream()
                .map(row -> new Results.AllergenView(row.allergenCode(), row.allergenName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Results.FormulaAllergenView getFormulaAllergens(long formulaId) {
        requireFormula(formulaId);
        return buildAllergenView(requireAllergenReg(formulaId), false);
    }

    @Transactional(readOnly = true)
    public Results.ProductionLineView getLine(String lineCode) {
        return buildLineView(repository.findProductionLine(lineCode)
                .orElseThrow(() -> notFoundLine(lineCode)));
    }

    @Transactional(readOnly = true)
    public Results.ClearanceCertificateView getCertificate(long certificateId) {
        ClearanceCertificateRow row = repository.findClearanceCertificate(certificateId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CERTIFICATE_NOT_FOUND",
                        "清线凭证不存在：" + certificateId));
        return buildCertificateView(row, false, clock.nowMillis());
    }

    @Transactional(readOnly = true)
    public List<Results.ControlledProductionView> listControlledProductions(String lineCode) {
        if (repository.findProductionLine(lineCode).isEmpty()) {
            throw notFoundLine(lineCode);
        }
        return repository.findControlledProductionsByLine(lineCode).stream()
                .map(row -> buildProductionView(row, false))
                .toList();
    }

    // ---------------- 内部辅助 ----------------

    /** 校验并锁定清线凭证：线路、占用版本、2 小时 UTC 窗口（恰满失效）与过敏原覆盖范围。 */
    private Long requireValidCertificate(ControlledProductionRequest request,
            ProductionLineRow line, Set<String> uncovered, long occurredAt) {
        if (request.certificateId() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CERTIFICATE_REQUIRED",
                    "前批含新批未声明过敏原，必须引用有效清线凭证：" + uncovered);
        }
        ClearanceCertificateRow certificate =
                repository.findClearanceCertificateForUpdate(request.certificateId())
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                "CERTIFICATE_NOT_FOUND",
                                "清线凭证不存在：" + request.certificateId()));
        if (!"VALID".equals(certificate.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CERTIFICATE_NOT_VALID",
                    "清线凭证已核销或失效，不能重复使用");
        }
        if (!certificate.lineCode().equals(line.lineCode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CERTIFICATE_LINE_MISMATCH",
                    "清线凭证绑定线路与量产线路不一致");
        }
        if (certificate.occupiedVersion() != line.occupiedVersion()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CERTIFICATE_LINE_VERSION_MISMATCH",
                    "清线凭证确认时占用版本与线路当前占用版本不一致：confirmed="
                            + certificate.occupiedVersion() + ", current="
                            + line.occupiedVersion());
        }
        if (occurredAt < certificate.confirmedAt()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CERTIFICATE_NOT_IN_EFFECT",
                    "生产时刻早于清线凭证双人确认时刻");
        }
        long validUntil = certificate.confirmedAt() + CLEARANCE_VALIDITY_MILLIS;
        if (occurredAt >= validUntil) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CERTIFICATE_EXPIRED",
                    "清线凭证已超过确认后 2 小时有效期（恰满 2 小时失效）");
        }
        if (!certificate.coveredAllergens().containsAll(uncovered)) {
            Set<String> missing = new TreeSet<>(uncovered);
            certificate.coveredAllergens().forEach(missing::remove);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CERTIFICATE_SCOPE_INSUFFICIENT",
                    "清线凭证未覆盖全部带入过敏原，缺少：" + missing);
        }
        return certificate.id();
    }

    private boolean isOverdueWatch(FilingRow filing) {
        return "REVIEW_WATCH".equals(filing.status())
                && clock.todayEpochDay() > filing.watchStartDay() + ComplianceService.REVIEW_GRACE_DAYS;
    }

    private void requireEffectiveFiling(FilingRow filing) {
        if ("CANCELLED".equals(filing.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILING_NOT_EFFECTIVE",
                    "备案已注销，受控量产拒绝");
        }
        if ("SUSPENDED".equals(filing.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILING_NOT_EFFECTIVE",
                    "备案已暂停，受控量产拒绝");
        }
        if (!"ACTIVE".equals(filing.status()) && !"REVIEW_WATCH".equals(filing.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILING_NOT_EFFECTIVE",
                    "备案当前状态不受理受控量产：" + filing.status());
        }
        if (isOverdueWatch(filing)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILING_NOT_EFFECTIVE",
                    "备案复审观察已逾期，受控量产拒绝");
        }
    }

    private FormulaRow requireFormula(Long formulaId) {
        if (formulaId == null) {
            throw invalid("formulaId 不能为空");
        }
        return repository.findFormulaById(formulaId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FORMULA_NOT_FOUND",
                        "配方不存在：" + formulaId));
    }

    private FormulaAllergenRegRow requireAllergenReg(long formulaId) {
        return repository.findFormulaAllergenReg(formulaId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ALLERGEN_REG_NOT_FOUND",
                        "配方版本尚未登记过敏原集合：" + formulaId));
    }

    private static ApiException notFoundLine(String lineCode) {
        return new ApiException(HttpStatus.NOT_FOUND, "LINE_NOT_FOUND", "生产线不存在：" + lineCode);
    }

    private static ApiException notFoundFiling(long filingId) {
        return new ApiException(HttpStatus.NOT_FOUND, "FILING_NOT_FOUND",
                "备案不存在：" + filingId);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM", message);
    }

    /** 校验过敏原编码集合：非空去空白、目录存在、不允许重复编码（完整集合语义）。 */
    private List<String> normalizeAllergenCodes(List<String> codes) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String code : codes == null ? List.<String>of() : codes) {
            if (code == null || code.isBlank()) {
                throw invalid("过敏原编码不能为空");
            }
            String trimmed = code.trim();
            if (repository.findAllergen(trimmed).isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ALLERGEN_NOT_FOUND",
                        "过敏原目录中不存在：" + trimmed);
            }
            if (!normalized.add(trimmed)) {
                throw invalid("过敏原集合不能包含重复编码：" + trimmed);
            }
        }
        return normalized.stream().sorted().toList();
    }

    private static Results.FormulaAllergenView buildAllergenView(FormulaAllergenRegRow row,
            boolean replayed) {
        return new Results.FormulaAllergenView(row.formulaId(), row.version(), row.allergens(),
                row.updatedAt(), replayed);
    }

    private static Results.ProductionLineView buildLineView(ProductionLineRow row) {
        return new Results.ProductionLineView(row.lineCode(), row.lineName(), row.lastFormulaId(),
                row.occupiedVersion(), row.version(), row.updatedAt());
    }

    private static Results.ClearanceCertificateView buildCertificateView(
            ClearanceCertificateRow row, boolean replayed, long now) {
        return new Results.ClearanceCertificateView(row.id(), row.lineCode(), row.operatorId(),
                row.reviewerId(), row.cleanedAt(), row.confirmedAt(),
                row.confirmedAt() + CLEARANCE_VALIDITY_MILLIS, row.occupiedVersion(),
                row.coveredAllergens(), row.status(), row.consumedAt(),
                row.controlledProductionId(), now, replayed);
    }

    private static Results.ControlledProductionView buildProductionView(
            ControlledProductionRow row, boolean replayed) {
        return new Results.ControlledProductionView(row.id(), row.lineCode(), row.filingId(),
                row.formulaId(), row.batchNo(),
                row.quantityKg().stripTrailingZeros().toPlainString(), row.occupiedVersion(),
                row.certificateId(), row.occurredAt(), replayed);
    }
}
